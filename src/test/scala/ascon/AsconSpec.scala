package ascon

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import TestUtil._

/** Ascon core end-to-end through StreamTop against the golden model (Python-exported vectors). */
class AsconSpec extends AnyFlatSpec with ChiselScalatestTester {
  val Key   = hex("000102030405060708090a0b0c0d0e0f")
  val Nonce = hex("101112131415161718191a1b1c1d1e1f")

  def setup(dut: StreamTop, key: Array[Byte], nonce: Array[Byte], dec: Boolean): Unit = {
    dut.io.key.poke(leBig(key.toSeq).U(128.W))
    dut.io.nonce.poke(leBig(nonce.toSeq).U(128.W))
    dut.io.decrypt.poke(dec.B)
  }

  private def encryptVectors = vecLines("vectors/aead.vec").filter(l => l(0) == "A" && l(4) != "-" && hex(l(4)).length % 16 == 0)

  for (rpc <- Seq(1, 2, 4)) {
    s"Ascon core (rpc=$rpc)" should "encrypt block-aligned golden vectors (ct || tag)" in {
      for (l <- encryptVectors) {
        val pt = hex(l(4)); val exp = hex(l(5))
        test(new StreamTop(Ascon(rpc), pt.length)) { dut =>
          setup(dut, hex(l(1)), hex(l(2)), dec = false)
          val out = streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, pt.toSeq, pt.length + 16, 0.7, 0.7)
          assert(toHex(out) == toHex(exp.toSeq), s"len ${pt.length}")
        }
      }
    }
    it should "decrypt golden vectors and report tagOk" in {
      for (l <- encryptVectors) {
        val pt = hex(l(4)); val ct = hex(l(5))
        test(new StreamTop(Ascon(rpc), pt.length)) { dut =>
          setup(dut, hex(l(1)), hex(l(2)), dec = true)
          var ok: Option[Boolean] = None
          val out = streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, ct.toSeq, pt.length, 0.7, 0.7,
            perCycle = () => if (dut.io.tagValid.peekBoolean()) ok = Some(dut.io.tagOk.peekBoolean()))
          var n = 0; while (ok.isEmpty && n < 200) { dut.clock.step(); n += 1; if (dut.io.tagValid.peekBoolean()) ok = Some(dut.io.tagOk.peekBoolean()) }
          assert(toHex(out) == toHex(pt.toSeq))
          assert(ok == Some(true), "tag should verify")
        }
      }
    }
  }

  "Ascon core" should "reject a tampered tag and a tampered ciphertext" in {
    val l = encryptVectors.find(l => hex(l(4)).length == 32).get
    val pt = hex(l(4)); val ct = hex(l(5))
    for (flip <- Seq(ct.length - 1, 3)) {
      val bad = ct.clone(); bad(flip) = (bad(flip) ^ 1).toByte
      test(new StreamTop(Ascon(4), pt.length)) { dut =>
        setup(dut, hex(l(1)), hex(l(2)), dec = true)
        var ok: Option[Boolean] = None
        streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, bad.toSeq, pt.length, 1.0, 1.0,
          perCycle = () => if (dut.io.tagValid.peekBoolean()) ok = Some(dut.io.tagOk.peekBoolean()))
        var n = 0; while (ok.isEmpty && n < 200) { dut.clock.step(); n += 1; if (dut.io.tagValid.peekBoolean()) ok = Some(dut.io.tagOk.peekBoolean()) }
        assert(ok == Some(false), s"tampered byte $flip must fail the tag check")
      }
    }
  }

  it should "match the golden per-round states for a full encryption (rpc=1)" in {
    val lines = Source_trace()
    val expected = lines.filter(_(1) != "-").map(l => (l(1).toInt, (0 until 5).map(i => big(l(2 + i)))))
    val pt = (0 until 32).map(_.toByte)
    val got = scala.collection.mutable.ArrayBuffer[(Int, Seq[BigInt])]()
    test(new StreamTop(Ascon(1), 32)) { dut =>
      setup(dut, Key, Nonce, dec = false)
      streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, pt, 48, 1.0, 1.0,
        perCycle = () => if (dut.io.dbgRoundDone.peekBoolean())
          got += ((dut.io.dbgRoundIdx.peekInt().toInt, (0 until 5).map(i => dut.io.dbgState(i).peekInt()))))
    }
    assert(got.size == expected.size, s"round count ${got.size} vs ${expected.size}")
    for ((g, (e, n)) <- got.zip(expected).zipWithIndex.map { case (x, n) => (x._1, (x._2, n)) }) {
      assert(g._1 == e._1, s"step $n: round index ${g._1} vs ${e._1}")
      assert(g._2 == e._2, s"step $n (round index ${e._1}): state differs from golden model")
    }
  }

  private def Source_trace(): Seq[Array[String]] = vecLines("vectors/trace_enc_32B.txt")

  "Padding edge case" should "agree with the golden model for a 16384-byte (aligned) message" in {
    val l = vecLines("vectors/aead_16384.vec").head
    val pt = Array.tabulate(16384)(i => ((i * 7) & 0xff).toByte)
    val expTag = hex(l(3))
    test(new StreamTop(Ascon(4), 16384)).withAnnotations(Seq()) { dut =>
      setup(dut, hex(l(1)), hex(l(2)), dec = false)
      val out = streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, pt.toSeq, 16384 + 16, 1.0, 1.0)
      assert(out.length == 16400, "exactly 16 extra bytes: tag only, no ciphertext from the padding-only block")
      assert(toHex(out.takeRight(16)) == toHex(expTag.toSeq), "tag mismatch vs golden model")
    }
  }
}
