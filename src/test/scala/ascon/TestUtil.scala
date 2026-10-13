package ascon

import chisel3._
import chiseltest._
import java.awt.image.{BufferedImage, DataBufferByte}
import java.io.File
import javax.imageio.ImageIO
import scala.collection.mutable.ArrayBuffer
import scala.io.Source

object TestUtil {
  def hex(s: String): Array[Byte] = if (s == "-") Array.empty[Byte] else s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  def toHex(b: Seq[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  def big(s: String): BigInt = BigInt(s, 16)

  /** Non-comment vector lines split on whitespace. */
  def vecLines(path: String): Seq[Array[String]] =
    Source.fromFile(path).getLines().filterNot(l => l.startsWith("#") || l.trim.isEmpty).map(_.trim.split("\\s+")).toSeq

  def leBig(b: Seq[Byte]): BigInt = BigInt(1, b.reverse.map(x => x.toByte).toArray)

  def readGray(path: String): Array[Byte] = {
    val img = ImageIO.read(new File(path))
    require(img.getType == BufferedImage.TYPE_BYTE_GRAY, s"expected gray image, got type ${img.getType}")
    require(img.getWidth == 128 && img.getHeight == 128)
    img.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData.clone()
  }
  def writeGray(path: String, bytes: Array[Byte]): Unit = {
    val img = new BufferedImage(128, 128, BufferedImage.TYPE_BYTE_GRAY)
    img.getRaster.setDataElements(0, 0, 128, 128, bytes)
    new File(path).getParentFile.mkdirs()
    ImageIO.write(img, "png", new File(path))
  }
  /** Deterministic test image; must stay identical to imgtool.gen in python/imgtool.py. */
  def genImage(): Array[Byte] = {
    val b = new Array[Byte](128 * 128)
    for (y <- 0 until 128; x <- 0 until 128) {
      var v = (x + y) * 255 / 254
      if ((x - 64) * (x - 64) + (y - 64) * (y - 64) < 900) v = 255 - v
      if ((x / 16 + y / 16) % 2 == 0 && x > 100 && x < 120 && y > 8 && y < 28) v = 255
      b(y * 128 + x) = v.toByte
    }
    b
  }
  def readBin(path: String): Array[Byte] = java.nio.file.Files.readAllBytes(new File(path).toPath)

  /** Drive a Decoupled byte stream in and collect `expectedOut` bytes from out, with random
    * valid/ready stalls. Keeps valid asserted once offered until accepted (protocol rule).
    * Calls `perCycle` after each clock edge (for monitors). */
  def streamBytes(clock: Clock, in: chisel3.util.DecoupledIO[UInt], out: chisel3.util.DecoupledIO[UInt],
                  data: Seq[Byte], expectedOut: Int, pIn: Double, pOut: Double, seed: Int = 1,
                  maxCycles: Int = 5000000, perCycle: () => Unit = () => ()): Seq[Byte] = {
    val rng = new scala.util.Random(seed)
    val got = ArrayBuffer[Byte]()
    var idx = 0; var offering = false; var cycles = 0
    while ((got.size < expectedOut || idx < data.size) && cycles < maxCycles) {
      if (!offering && idx < data.size && rng.nextDouble() < pIn) offering = true
      in.valid.poke(offering.B)
      if (offering) in.bits.poke((data(idx) & 0xff).U)
      val rdy = rng.nextDouble() < pOut
      out.ready.poke(rdy.B)
      val inFire = offering && in.ready.peekBoolean()
      if (rdy && out.valid.peekBoolean()) got += out.bits.peekInt().toByte
      clock.step()
      perCycle()
      if (inFire) { idx += 1; offering = false }
      cycles += 1
    }
    in.valid.poke(false.B)
    assert(got.size == expectedOut, s"timeout: got ${got.size}/$expectedOut bytes after $cycles cycles")
    got.toSeq
  }
}
