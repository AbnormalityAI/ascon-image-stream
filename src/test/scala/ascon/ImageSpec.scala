package ascon

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import TestUtil._

/** End-to-end: 128x128 grayscale PNG streamed through the DUT, output PNG written and compared. */
class ImageSpec extends AnyFlatSpec with ChiselScalatestTester {
  val Key   = hex("000102030405060708090a0b0c0d0e0f")
  val Nonce = hex("101112131415161718191a1b1c1d1e1f")
  // generated, not committed: write it out as PNG, read it back through ImageIO (real PNG round trip)
  lazy val img = { writeGray("target/e2e/in.png", genImage()); readGray("target/e2e/in.png") }

  "Passthrough pipeline" should "output an image identical to the input (random backpressure)" in {
    assert(img.length == 16384)
    test(new StreamTop(Passthrough, 16384)) { dut =>
      val out = streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, img.toSeq, 16384, 0.8, 0.6, seed = 5)
      writeGray("target/e2e/out_passthrough.png", out.toArray)
      assert(readGray("target/e2e/out_passthrough.png").toSeq == img.toSeq)
      assert(out == img.toSeq)
    }
  }

  it should "handle several images back to back" in {
    test(new StreamTop(Passthrough, 16384)) { dut =>
      for (round <- 0 until 2) {
        val out = streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, img.toSeq, 16384, 1.0, 1.0, seed = round)
        assert(out == img.toSeq)
      }
    }
  }

  "Ascon pipeline" should "encrypt the image to the golden ciphertext||tag, then decrypt it back" in {
    val golden = hex(scala.io.Source.fromFile("vectors/image_enc.hex").getLines().filterNot(_.startsWith("#")).mkString.trim)
    assert(golden.length == 16400)
    test(new StreamTop(Ascon(4), 16384)) { dut =>
      dut.io.key.poke(leBig(Key.toSeq).U(128.W)); dut.io.nonce.poke(leBig(Nonce.toSeq).U(128.W))
      dut.io.decrypt.poke(false.B)
      val enc = streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, img.toSeq, 16400, 0.9, 0.9, seed = 9)
      assert(enc == golden.toSeq, "hardware ciphertext||tag differs from Python golden model")
      writeGray("target/e2e/noise.png", enc.take(16384).toArray)
      dut.clock.step(5)
      dut.io.decrypt.poke(true.B)
      var ok: Option[Boolean] = None
      val dec = streamBytes(dut.clock, dut.io.stream.in, dut.io.stream.out, enc, 16384, 0.9, 0.9, seed = 10,
        perCycle = () => if (dut.io.tagValid.peekBoolean()) ok = Some(dut.io.tagOk.peekBoolean()))
      var n = 0; while (ok.isEmpty && n < 200) { dut.clock.step(); n += 1; if (dut.io.tagValid.peekBoolean()) ok = Some(dut.io.tagOk.peekBoolean()) }
      assert(ok == Some(true), "tag must verify")
      writeGray("target/e2e/out_decrypted.png", dec.toArray)
      assert(readGray("target/e2e/out_decrypted.png").toSeq == img.toSeq, "decrypted image differs from input")
    }
  }
}
