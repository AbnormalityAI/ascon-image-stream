package ascon

import chisel3._
import chisel3.util._

/** Fixed byte-stream interface: Decoupled(UInt(8.W)) in and out. */
class ByteStreamIO extends Bundle {
  val in  = Flipped(Decoupled(UInt(8.W)))
  val out = Decoupled(UInt(8.W))
}

/** 128-bit block (16 bytes, byte 0 in bits 7:0, i.e. little-endian as Ascon loads words). */
class Block extends Bundle {
  val data = UInt(128.W)
  val last = Bool() // last block of the message (see AsconCore for exact meaning)
}

/** Interface every cipher core implements, so cores are interchangeable in StreamTop. */
class CipherCoreIO extends Bundle {
  val in       = Flipped(Decoupled(new Block))
  val out      = Decoupled(new Block)
  val key      = Input(UInt(128.W))
  val nonce    = Input(UInt(128.W))
  val decrypt  = Input(Bool())
  val tagValid = Output(Bool()) // pulse: decrypt tag check finished
  val tagOk    = Output(Bool()) // result of the check, held until next message starts
  // debug: permutation state after each round (valid when dbgRoundDone)
  val dbgRoundDone = Output(Bool())
  val dbgRoundIdx  = Output(UInt(4.W))
  val dbgState     = Output(Vec(5, UInt(64.W)))
}

/** 16 bytes -> one 128-bit block. `lastIndex` is the 0-based index of the block flagged `last`. */
class Packer(counterBits: Int = 16) extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(UInt(8.W)))
    val out       = Decoupled(new Block)
    val lastIndex = Input(UInt(counterBits.W))
  })
  val buf    = Reg(Vec(16, UInt(8.W)))
  val cnt    = RegInit(0.U(5.W))
  val blkIdx = RegInit(0.U(counterBits.W))
  val full   = cnt === 16.U

  io.in.ready := !full
  when(io.in.fire) {
    buf(cnt(3, 0)) := io.in.bits
    cnt := cnt + 1.U
  }
  io.out.valid := full
  io.out.bits.data := Cat(buf.reverse) // buf(0) ends up in bits 7:0
  io.out.bits.last := blkIdx === io.lastIndex
  when(io.out.fire) {
    cnt := 0.U
    blkIdx := Mux(blkIdx === io.lastIndex, 0.U, blkIdx + 1.U)
  }
}

/** One 128-bit block -> 16 bytes, byte 0 (bits 7:0) first. */
class Unpacker extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Block))
    val out = Decoupled(UInt(8.W))
  })
  val data = Reg(UInt(128.W))
  val busy = RegInit(false.B)
  val idx  = RegInit(0.U(4.W))

  io.in.ready := !busy
  when(io.in.fire) {
    data := io.in.bits.data
    busy := true.B
    idx := 0.U
  }
  val bytes = VecInit.tabulate(16)(i => data(8 * i + 7, 8 * i))
  io.out.valid := busy
  io.out.bits := bytes(idx)
  when(io.out.fire) {
    idx := idx + 1.U
    when(idx === 15.U) { busy := false.B }
  }
}

/** Passthrough core: a one-entry ready/valid register slice; ignores key/nonce/mode. */
class PassthroughCore extends Module {
  val io = IO(new CipherCoreIO)
  val valid = RegInit(false.B)
  val bits  = Reg(new Block)
  io.in.ready := !valid
  when(io.in.fire) { valid := true.B; bits := io.in.bits }
  io.out.valid := valid
  io.out.bits := bits
  when(io.out.fire) { valid := false.B }
  io.tagValid := false.B
  io.tagOk := false.B
  io.dbgRoundDone := false.B
  io.dbgRoundIdx := 0.U
  io.dbgState := VecInit(Seq.fill(5)(0.U(64.W)))
}

sealed trait CipherKind
case object Passthrough extends CipherKind
case class Ascon(roundsPerCycle: Int = 1) extends CipherKind

/** bytes -> Packer -> core -> Unpacker -> bytes.
  *
  * `messageBytes` is the data length per message (must be a multiple of 16 for Ascon). For Ascon
  * encryption the output is messageBytes + 16 bytes (tag); for decryption the input is
  * messageBytes + 16 bytes (ciphertext || tag) and the output messageBytes bytes of plaintext.
  */
class StreamTop(cipher: CipherKind = Passthrough, messageBytes: Int = 16384) extends Module {
  require(messageBytes > 0 && messageBytes % 16 == 0, "messageBytes must be a positive multiple of 16")
  val blocks = messageBytes / 16
  val io = IO(new Bundle {
    val stream   = new ByteStreamIO
    val key      = Input(UInt(128.W))
    val nonce    = Input(UInt(128.W))
    val decrypt  = Input(Bool())
    val tagValid = Output(Bool())
    val tagOk    = Output(Bool())
    val dbgRoundDone = Output(Bool())
    val dbgRoundIdx  = Output(UInt(4.W))
    val dbgState     = Output(Vec(5, UInt(64.W)))
  })
  val cbits    = log2Up(blocks + 2)
  val packer   = Module(new Packer(cbits))
  val unpacker = Module(new Unpacker)
  val core: CipherCoreIO = cipher match {
    case Passthrough => Module(new PassthroughCore).io
    case Ascon(r)    => Module(new AsconCore(r)).io
  }
  packer.io.in <> io.stream.in
  packer.io.lastIndex := (cipher match {
    case Passthrough => (blocks - 1).U
    case _           => Mux(io.decrypt, blocks.U, (blocks - 1).U) // decrypt: tag block is the last
  })
  core.in <> packer.io.out
  unpacker.io.in <> core.out
  io.stream.out <> unpacker.io.out
  core.key := io.key
  core.nonce := io.nonce
  core.decrypt := io.decrypt
  io.tagValid := core.tagValid
  io.tagOk := core.tagOk
  io.dbgRoundDone := core.dbgRoundDone
  io.dbgRoundIdx := core.dbgRoundIdx
  io.dbgState := core.dbgState
}
