package ascon

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import TestUtil._

class StreamSpec extends AnyFlatSpec with ChiselScalatestTester {
  "PassthroughCore" should "pass blocks unchanged under random backpressure" in {
    test(new PassthroughCore) { dut =>
      val rng = new scala.util.Random(7)
      val blocks = Seq.fill(200)(BigInt(128, rng))
      var sent = 0; var offering = false; val got = scala.collection.mutable.ArrayBuffer[BigInt]()
      var cycles = 0
      while (got.size < blocks.size && cycles < 100000) {
        if (!offering && sent < blocks.size && rng.nextBoolean()) offering = true
        dut.io.in.valid.poke(offering.B)
        if (offering) { dut.io.in.bits.data.poke(blocks(sent).U(128.W)); dut.io.in.bits.last.poke((sent == blocks.size - 1).B) }
        val rdy = rng.nextInt(4) == 0 // heavy backpressure
        dut.io.out.ready.poke(rdy.B)
        val inFire = offering && dut.io.in.ready.peekBoolean()
        if (rdy && dut.io.out.valid.peekBoolean()) got += dut.io.out.bits.data.peekInt()
        dut.clock.step()
        if (inFire) { sent += 1; offering = false }
        cycles += 1
      }
      assert(got == blocks)
    }
  }

  it should "hold data stable while stalled" in {
    test(new PassthroughCore) { dut =>
      dut.io.out.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.data.poke(BigInt("abcdef", 16).U(128.W))
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      assert(!dut.io.in.ready.peekBoolean(), "must not accept a second block while full")
      for (_ <- 0 until 5) { dut.clock.step(); assert(dut.io.out.valid.peekBoolean()); assert(dut.io.out.bits.data.peekInt() == BigInt("abcdef", 16)) }
    }
  }

  /** Packer feeding Unpacker directly. */
  class PackUnpack extends Module {
    val io = IO(new ByteStreamIO)
    val p = Module(new Packer(8)); val u = Module(new Unpacker)
    p.io.in <> io.in; p.io.lastIndex := 3.U
    u.io.in <> p.io.out; io.out <> u.io.out
  }

  "Packer/Unpacker" should "round-trip random bytes under random stalls" in {
    for ((pIn, pOut) <- Seq((1.0, 1.0), (0.3, 1.0), (1.0, 0.3), (0.5, 0.5))) {
      test(new PackUnpack) { dut =>
        val rng = new scala.util.Random(3)
        val data = Array.fill(16 * 40)(rng.nextInt(256).toByte).toSeq
        val out = streamBytes(dut.clock, dut.io.in, dut.io.out, data, data.size, pIn, pOut, seed = 11)
        assert(out == data)
      }
    }
  }

  "Packer" should "pack byte 0 into bits 7:0 and flag the configured last block" in {
    test(new Packer(8)) { dut =>
      dut.io.lastIndex.poke(1.U)
      dut.io.out.ready.poke(false.B)
      for (blk <- 0 until 2) {
        for (i <- 0 until 16) {
          dut.io.in.valid.poke(true.B); dut.io.in.bits.poke((blk * 16 + i + 1).U); dut.clock.step()
        }
        dut.io.in.valid.poke(false.B)
        assert(dut.io.out.valid.peekBoolean())
        val exp = (0 until 16).map(i => BigInt(blk * 16 + i + 1) << (8 * i)).sum
        assert(dut.io.out.bits.data.peekInt() == exp)
        assert(dut.io.out.bits.last.peekBoolean() == (blk == 1))
        dut.io.out.ready.poke(true.B); dut.clock.step(); dut.io.out.ready.poke(false.B)
      }
    }
  }
}
