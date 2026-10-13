package ascon

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import TestUtil._

/** Combinational round function vs golden model (vectors/round.vec), all 12 round constants. */
class RoundSpec extends AnyFlatSpec with ChiselScalatestTester {
  "AsconRound" should "match the golden model for every round index" in {
    val lines = vecLines("vectors/round.vec")
    assert(lines.nonEmpty)
    test(new AsconRoundModule) { dut =>
      for (l <- lines) {
        assert(l(0) == "R")
        dut.io.roundIdx.poke(l(1).toInt.U)
        for (i <- 0 until 5) dut.io.in(i).poke(big(l(2 + i)).U(64.W))
        dut.clock.step()
        for (i <- 0 until 5)
          assert(dut.io.out(i).peekInt() == big(l(7 + i)), s"round ${l(1)} word x$i, input ${l.slice(2, 7).mkString(",")}")
      }
    }
  }
}
