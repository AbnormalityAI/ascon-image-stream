package ascon

import chisel3._
import chisel3.util._

/** Ascon-AEAD128 (NIST SP 800-232) core, empty associated data, whole 16-byte blocks.
  *
  * Block stream protocol (see README):
  *  - encrypt: input = plaintext blocks, `last` on the final one. Output = one ciphertext block per
  *    input block, then one tag block (`last` set). The padding-only block is absorbed internally
  *    after the final data block and produces no output.
  *  - decrypt: input = ciphertext blocks then the tag block, `last` on the TAG block. Output =
  *    plaintext blocks (released as they are computed, BEFORE the tag is checked). `tagValid`
  *    pulses when the check finishes, `tagOk` holds the result.
  *  - A message starts when the core is idle and the first block is valid. key/nonce/decrypt are
  *    sampled then. At least one data block is required. Never reuse a nonce with the same key.
  */
class AsconCore(roundsPerCycle: Int = 1) extends Module {
  val io = IO(new CipherCoreIO)

  object S extends ChiselEnum { val Idle, Init, InitFix, Data, Perm8, Final, Tag = Value }
  val state = RegInit(S.Idle)

  val perm = Module(new AsconPermutation(roundsPerCycle))
  val st   = perm.io.state

  val IV = "h00001000808C0001".U(64.W)

  val keyR     = Reg(UInt(128.W))
  val decR     = RegInit(false.B)
  val lastR    = RegInit(false.B)
  val tagR     = Reg(UInt(128.W))
  val outValid = RegInit(false.B)
  val outBits  = Reg(new Block)
  val tagOkR   = RegInit(false.B)
  val tagValidR = RegInit(false.B)

  val k0 = keyR(63, 0)
  val k1 = keyR(127, 64)

  // defaults
  perm.io.start := false.B
  perm.io.startState := VecInit(Seq.fill(5)(0.U(64.W)))
  perm.io.isP12 := true.B
  perm.io.load := false.B
  perm.io.loadState := VecInit(Seq.fill(5)(0.U(64.W)))
  tagValidR := false.B

  io.out.valid := outValid
  io.out.bits := outBits
  when(io.out.fire) { outValid := false.B }

  io.in.ready := state === S.Data && !outValid

  // finalization entry: pad block (x0 ^= 1), then key xor into x2/x3, then p12
  def startFinal(): Unit = {
    perm.io.start := true.B
    perm.io.isP12 := true.B
    perm.io.startState := VecInit(Seq(st(0) ^ 1.U, st(1), st(2) ^ k0, st(3) ^ k1, st(4)))
    state := S.Final
  }

  val tagWord = Cat(st(4) ^ k1, st(3) ^ k0)

  switch(state) {
    is(S.Idle) {
      when(io.in.valid) {
        keyR := io.key
        decR := io.decrypt
        tagOkR := false.B
        perm.io.start := true.B
        perm.io.isP12 := true.B
        perm.io.startState := VecInit(Seq(IV, io.key(63, 0), io.key(127, 64), io.nonce(63, 0), io.nonce(127, 64)))
        state := S.Init
      }
    }
    is(S.Init) {
      when(perm.io.done) {
        perm.io.load := true.B
        // second key xor and domain separation (empty AD)
        perm.io.loadState := VecInit(Seq(st(0), st(1), st(2), st(3) ^ k0, st(4) ^ k1 ^ (BigInt(1) << 63).U(64.W)))
        state := S.Data
      }
    }
    is(S.Data) {
      when(io.in.fire) {
        val bl = io.in.bits.data(63, 0)
        val bh = io.in.bits.data(127, 64)
        when(decR && io.in.bits.last) {
          // tag block: no output, absorb padding-only block and finalize
          tagR := io.in.bits.data
          startFinal()
        }.otherwise {
          val x0 = st(0) ^ bl
          val x1 = st(1) ^ bh
          outBits.data := Cat(x1, x0)
          outBits.last := false.B
          outValid := true.B
          lastR := io.in.bits.last && !decR
          perm.io.start := true.B
          perm.io.isP12 := false.B
          // encrypt: state absorbs plaintext (xor result); decrypt: state is replaced by ciphertext
          perm.io.startState := VecInit(Seq(Mux(decR, bl, x0), Mux(decR, bh, x1), st(2), st(3), st(4)))
          state := S.Perm8
        }
      }
    }
    is(S.Perm8) {
      when(perm.io.done) {
        when(lastR) { startFinal() }.otherwise { state := S.Data }
      }
    }
    is(S.Final) {
      when(perm.io.done) {
        when(decR) {
          tagOkR := tagWord === tagR
          tagValidR := true.B
          state := S.Idle
        }.otherwise {
          state := S.Tag
        }
      }
    }
    is(S.Tag) {
      when(!outValid) {
        outBits.data := tagWord
        outBits.last := true.B
        outValid := true.B
        state := S.Idle
      }
    }
  }

  io.tagValid := tagValidR
  io.tagOk := tagOkR
  io.dbgRoundDone := perm.io.roundDone
  io.dbgRoundIdx := perm.io.roundIdx
  io.dbgState := perm.io.state
}
