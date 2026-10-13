package ascon

import chisel3._
import chisel3.util._

/** Ascon round function on five 64-bit words: constant addition, S-box, linear layer. */
object AsconRound {
  /** Round constants for p12; p8 uses indices 4..11. */
  val Constants: Seq[Int] = Seq.tabulate(12)(i => 0xf0 - 0x0f * i)

  def rotr(x: UInt, n: Int): UInt = Cat(x(n - 1, 0), x(63, n))

  def apply(s: Seq[UInt], c: UInt): Seq[UInt] = {
    require(s.length == 5)
    // constant addition
    val x2c = s(2) ^ c
    // substitution layer (bitsliced 5-bit S-box)
    val a0 = s(0) ^ s(4)
    val a1 = s(1)
    val a2 = x2c ^ s(1)
    val a3 = s(3)
    val a4 = s(4) ^ s(3)
    val t0 = a0 ^ (~a1 & a2)
    val t1 = a1 ^ (~a2 & a3)
    val t2 = a2 ^ (~a3 & a4)
    val t3 = a3 ^ (~a4 & a0)
    val t4 = a4 ^ (~a0 & a1)
    val u1 = t1 ^ t0
    val u0 = t0 ^ t4
    val u3 = t3 ^ t2
    val u2 = ~t2
    // linear diffusion layer
    Seq(
      u0 ^ rotr(u0, 19) ^ rotr(u0, 28),
      u1 ^ rotr(u1, 61) ^ rotr(u1, 39),
      u2 ^ rotr(u2, 1) ^ rotr(u2, 6),
      u3 ^ rotr(u3, 10) ^ rotr(u3, 17),
      t4 ^ rotr(t4, 7) ^ rotr(t4, 41)
    )
  }
}

/** Combinational single-round module (for unit tests / debugging). */
class AsconRoundModule extends Module {
  val io = IO(new Bundle {
    val roundIdx = Input(UInt(4.W)) // 0..11
    val in       = Input(Vec(5, UInt(64.W)))
    val out      = Output(Vec(5, UInt(64.W)))
  })
  val consts = VecInit(AsconRound.Constants.map(_.U(8.W)))
  io.out := VecInit(AsconRound(io.in, consts(io.roundIdx)))
}
