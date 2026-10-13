package ascon

import chisel3._
import chisel3.util._

/** Ascon permutation generator: state register, round counter, `roundsPerCycle` unrolled rounds.
  * p12 starts at round index 0, p8 at round index 4 (constants 0xf0 - 0x0f*i).
  * roundsPerCycle must divide both 12 and 8, so 1, 2 or 4.
  *
  * Usage: pulse `start` (with `startState`, `isP12`) while !busy. `done` pulses for one cycle once
  * the final round is in the register; `state` holds the result until the next start/load.
  * `load` overwrites the state register without running rounds (used by the AEAD controller for
  * key/domain-separation xors).
  */
class AsconPermutation(val roundsPerCycle: Int = 1) extends Module {
  require(Seq(1, 2, 4).contains(roundsPerCycle), "roundsPerCycle must be 1, 2 or 4")
  val io = IO(new Bundle {
    val start      = Input(Bool())
    val startState = Input(Vec(5, UInt(64.W)))
    val isP12      = Input(Bool())
    val load       = Input(Bool())
    val loadState  = Input(Vec(5, UInt(64.W)))
    val state      = Output(Vec(5, UInt(64.W)))
    val busy       = Output(Bool())
    val done       = Output(Bool())
    // debug: roundDone is high in the cycle after a round step; state then holds the output of
    // round index roundIdx (the last round of that step when roundsPerCycle > 1).
    val roundDone  = Output(Bool())
    val roundIdx   = Output(UInt(4.W))
  })

  val st       = RegInit(VecInit(Seq.fill(5)(0.U(64.W))))
  val rnd      = RegInit(0.U(4.W))
  val active   = RegInit(false.B)
  val doneR    = RegInit(false.B)
  val roundDoneR = RegInit(false.B)
  val roundIdxR  = RegInit(0.U(4.W))
  val consts   = VecInit(AsconRound.Constants.map(_.U(8.W)))

  doneR := false.B
  roundDoneR := false.B

  when(io.start) {
    st := io.startState
    rnd := Mux(io.isP12, 0.U, 4.U)
    active := true.B
  }.elsewhen(io.load) {
    st := io.loadState
  }.elsewhen(active) {
    var s: Seq[UInt] = st
    for (i <- 0 until roundsPerCycle) s = AsconRound(s, consts(rnd + i.U))
    st := VecInit(s)
    roundDoneR := true.B
    roundIdxR := rnd + (roundsPerCycle - 1).U
    rnd := rnd + roundsPerCycle.U
    when(rnd + roundsPerCycle.U === 12.U) {
      active := false.B
      doneR := true.B
    }
  }

  io.state := st
  io.busy := active
  io.done := doneR
  io.roundDone := roundDoneR
  io.roundIdx := roundIdxR
}
