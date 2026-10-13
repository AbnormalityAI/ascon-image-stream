package ascon

import circt.stage.ChiselStage

/** Emit SystemVerilog: `sbt "runMain ascon.Main passthrough|ascon [roundsPerCycle]"`. */
object Main extends App {
  val kind: CipherKind = args.headOption match {
    case Some("ascon") => Ascon(args.lift(1).map(_.toInt).getOrElse(1))
    case _             => Passthrough
  }
  ChiselStage.emitSystemVerilogFile(new StreamTop(kind), Array("--target-dir", "generated"))
}
