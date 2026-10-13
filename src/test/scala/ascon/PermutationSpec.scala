package ascon

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import TestUtil._

/** Permutation generator vs golden model: p12/p8 results for 1, 2, 4 rounds per cycle,
  * plus per-round state check (first mismatching round is reported). */
class PermutationSpec extends AnyFlatSpec with ChiselScalatestTester {
  private def runPerm(dut: AsconPermutation, isP12: Boolean, in: Seq[BigInt]): Seq[BigInt] = {
    for (i <- 0 until 5) dut.io.startState(i).poke(in(i).U(64.W))
    dut.io.isP12.poke(isP12.B)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var n = 0
    while (!dut.io.done.peekBoolean()) { dut.clock.step(); n += 1; assert(n < 100, "permutation never finished") }
    val rounds = if (isP12) 12 else 8
    // one load cycle, then rounds/roundsPerCycle round cycles
    assert(n + 1 == rounds / dut.roundsPerCycle + 1, s"latency ${n + 1} cycles, expected ${rounds / dut.roundsPerCycle + 1}")
    (0 until 5).map(i => dut.io.state(i).peekInt())
  }

  for (rpc <- Seq(1, 2, 4)) {
    s"AsconPermutation(roundsPerCycle=$rpc)" should "match p12 and p8 golden vectors" in {
      val lines = vecLines("vectors/perm.vec")
      test(new AsconPermutation(rpc)) { dut =>
        for (l <- lines) {
          val out = runPerm(dut, l(1) == "12", (0 until 5).map(i => big(l(2 + i))))
          for (i <- 0 until 5) assert(out(i) == big(l(7 + i)), s"p${l(1)} x$i")
          assert(!dut.io.busy.peekBoolean())
        }
      }
    }
  }

  "AsconPermutation(1)" should "match the golden state after every round" in {
    val lines = vecLines("vectors/perm_trace.vec")
    test(new AsconPermutation(1)) { dut =>
      var cur: Option[(Int, Seq[BigInt])] = None
      var expected = Seq.empty[(Int, Seq[BigInt])]
      def check(): Unit = cur.foreach { case (rounds, in) =>
        for (i <- 0 until 5) dut.io.startState(i).poke(in(i).U(64.W))
        dut.io.isP12.poke((rounds == 12).B)
        dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
        var seen = 0
        while (seen < expected.size) {
          if (dut.io.roundDone.peekBoolean()) {
            val (idx, st) = expected(seen)
            assert(dut.io.roundIdx.peekInt() == idx, s"p$rounds: round index")
            for (i <- 0 until 5)
              assert(dut.io.state(i).peekInt() == st(i), s"p$rounds: first mismatch in round $idx word x$i")
            seen += 1
          }
          dut.clock.step()
        }
        dut.clock.step(2)
      }
      for (l <- lines) {
        if (l(0) == "S") { check(); cur = Some((l(1).toInt, (0 until 5).map(i => big(l(2 + i))))); expected = Seq.empty }
        else expected = expected :+ ((l(2).toInt, (0 until 5).map(i => big(l(3 + i)))))
      }
      check()
    }
  }
}
