/*
 Copyright (c) 2011, 2012, 2013 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel
import Node._
import java.io.File;
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import scala.sys.process._
import Reg._
import ChiselError._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import scala.collection.mutable.LinkedHashMap
import scala.collection.mutable.LinkedHashSet

object VerilogBackend {

  val keywords = HashSet[String](
    "always", "and", "assign", "attribute", "begin", "buf", "bufif0", "bufif1",
    "case", "casex", "casez", "cmos", "deassign", "default", "defparam",
    "disable", "edge", "else", "end", "endattribute", "endcase", "endfunction",
    "endmodule", "endprimitive", "endspecify", "endtable", "endtask", "event",
    "for", "force", "forever", "fork", "function", "highz0", "highz1", "if",
    "ifnone", "initial", "inout", "input", "integer", "initvar", "join",
    "medium", "module", "large", "macromodule", "nand", "negedge", "nmos",
    "nor", "not", "notif0", "notif1", "or", "output", "parameter", "pmos",
    "posedge", "primitive", "pull0", "pull1", "pulldown", "pullup", "rcmos",
    "real", "realtime", "reg", "release", "repeat", "rnmos", "rpmos", "rtran",
    "rtranif0", "rtranif1", "scalared", "signed", "small", "specify",
    "specparam", "strength", "strong0", "strong1", "supply0", "supply1",
    "table", "task", "time", "tran", "tranif0", "tranif1", "tri", "tri0",
    "tri1", "triand", "trior", "trireg", "unsigned", "vectored", "wait",
    "wand", "weak0", "weak1", "while", "wire", "wor", "xnor", "xor",
    "SYNTHESIS", "PRINTF_COND", "VCS")

  var traversalIndex = 0
}

class VerilogBackend extends Backend {
  val keywords = VerilogBackend.keywords
  override val needsLowering = Set("PriEnc", "OHToUInt", "Log2")

  override def isEmittingComponents: Boolean = true

  val flushedTexts = HashSet[String]()

  val memConfs = HashMap[String, String]()
  val compIndices = HashMap.empty[String,Int];

  private def getMemConfString: String =
    memConfs.map { case (conf, name) => "name " + name + " " + conf } reduceLeft(_ + _)

  private def getMemName(mem: Mem[_], configStr: String): String = {
    if (!memConfs.contains(configStr)) {
      /* Generates memory that are different in (depth, width, ports).
       All others, we return the previously generated name. */
      val compName = if (mem.component != null) {
        (if( !mem.component.moduleName.isEmpty ) {
          Backend.moduleNamePrefix + mem.component.moduleName
        } else {
          extractClassName(mem.component)
        } + "_")
      } else {
        Backend.moduleNamePrefix
      }
      // Generate a unique name for the memory module.
      val candidateName = compName + emitRef(mem)
      val memModuleName = if( compIndices contains candidateName ) {
        val count = (compIndices(candidateName) + 1)
        compIndices += (candidateName -> count)
        candidateName + "_" + count
      } else {
        compIndices += (candidateName -> 0)
        candidateName
      }
      memConfs += (configStr -> memModuleName)
    }
    memConfs(configStr)
  }

  def emitWidth(node: Node): String =
    if (node.width == 1) "" else "[" + (node.width-1) + ":0]"

  override def emitTmp(node: Node): String =
    emitRef(node)

  override def emitRef(node: Node): String = {
    node match {
      case x: Literal => emitLit(x.value, x.width)
      case _ => super.emitRef(node)
    }
  }

  private def emitLit(x: BigInt): String =
    emitLit(x, x.bitLength + (if (x < 0) 1 else 0))
  private def emitLit(x: BigInt, w: Int): String = {
    val unsigned = if (x < 0) (BigInt(1) << w) + x else x
    require(x >= 0)
    w + "'h" + unsigned.toString(16)
  }

  // $random only emits 32 bits; repeat its result to fill the Node
  private def emitRand(node: Node): String =
    "{" + ((node.width+31)/32) + "{$random}}"

  def emitPortDef(m: MemAccess, idx: Int): String = {
    def str(prefix: String, ports: (String, String)*): String =
      ports.toList.filter(_._2 != null)
        .map(p => "    ." + prefix + idx + p._1 + "(" + p._2 + ")")
        .reduceLeft(_ + ",\n" + _)

    m match {
      case r: MemSeqRead =>
        val addr = ("A", emitRef(r.addr))
        val en = ("E", emitRef(r.cond))
        val out = ("O", emitTmp(r))
        str("R", addr, en, out)

      case w: MemWrite =>
        val addr = ("A", emitRef(w.addr))
        val en = ("E", emitRef(w.cond))
        val data = ("I", emitRef(w.data))
        val mask = ("M", if (w.isMasked) emitRef(w.mask) else null)
        str("W", addr, en, data, mask)

      case rw: MemReadWrite =>
        val (r, w) = (rw.read, rw.write)
        val addr = ("A", emitRef(w.cond) + " ? " + emitRef(w.addr) + " : " + emitRef(r.addr))
        val en = ("E", emitRef(r.cond) + " || " + emitRef(w.cond))
        val write = ("W", emitRef(w.cond))
        val data = ("I", emitRef(w.data))
        val mask = ("M", if (w.isMasked) emitRef(w.mask) else null)
        val out = ("O", emitTmp(r))
        str("RW", addr, en, write, data, mask, out)
    }
  }

  def emitDef(c: Module): String = {
    val spacing = (if(c.verilog_parameters != "") " " else "");
    var res = "  " + c.moduleName + " " + c.verilog_parameters + spacing + c.name + "(";
    if (c.clocks.length > 0) {
      res = res + (c.clocks).map(x => "." + emitRef(x) + "(" + emitRef(x) + ")").reduceLeft(_ + ", " + _)
    }
    if (c.resets.size > 0 ) {    
      if (c.clocks.length > 0) res = res + ", "
      res = res + (c.resets.values.toList).map(x => "." + emitRef(x) + "(" + emitRef(x.inputs(0)) + ")").reduceLeft(_ + ", " + _)
    }
    var isFirst = true;
    val portDecs = new ArrayBuffer[StringBuilder]
    for ((n, w) <- c.wires) {
      if(n != "reset") {
        var portDec = "." + n + "( ";
        w match {
          case io: Bits  =>
            if (io.dir == INPUT) { // if reached, then input has consumers
              if (io.inputs.length == 0) {
                  // if (Driver.saveConnectionWarnings) {
                  //   ChiselError.warning("" + io + " UNCONNECTED IN " + io.component);
                  // } removed this warning because pruneUnconnectedIOs should have picked it up
                portDec = "//" + portDec
              } else if (io.inputs.length > 1) {
                  if (Driver.saveConnectionWarnings) {
                    ChiselError.warning("" + io + " CONNECTED TOO MUCH " + io.inputs.length);
                  }
                portDec = "//" + portDec
              } else if (!c.isWalked.contains(w)){
                  if (Driver.saveConnectionWarnings) {
                    ChiselError.warning(" UNUSED INPUT " + io + " OF " + c + " IS REMOVED");
                  }
                portDec = "//" + portDec
              } else {
                portDec += emitRef(io.inputs(0));
              }
            } else if(io.dir == OUTPUT) {
              if (io.consumers.length == 0) {
                  // if (Driver.saveConnectionWarnings) {
                  //   ChiselError.warning("" + io + " UNCONNECTED IN " + io.component + " BINDING " + c.findBinding(io));
                  // } removed this warning because pruneUnconnectedsIOs should have picked it up
                portDec = "//" + portDec
              } else {
                var consumer: Node = c.parent.findBinding(io);
                if (consumer == null) {
                  if (Driver.saveConnectionWarnings) {
                    ChiselError.warning("" + io + "(" + io.component + ") OUTPUT UNCONNECTED (" + io.consumers.length + ") IN " + c.parent);
                  }
                  portDec = "//" + portDec
                } else {
                  if (io.prune)
                    portDec = "//" + portDec + emitRef(consumer)
                  else
                    portDec += emitRef(consumer); // TODO: FIX THIS?
                }
              }
            }
        }
        portDec += " )"
        portDecs += new StringBuilder(portDec)
      }
    }
    val uncommentedPorts = portDecs.filter(!_.result.contains("//"))
    uncommentedPorts.slice(0, uncommentedPorts.length-1).map(_.append(","))
    portDecs.map(_.insert(0, "       "))
    if (c.clocks.length > 0 || c.resets.size > 0) res += ",\n" else res += "\n"
    res += portDecs.map(_.result).reduceLeft(_ + "\n" + _)
    res += "\n  );\n";
    if (c.wires.map(_._2.driveRand).reduceLeft(_ || _)) {
      res += "  `ifndef SYNTHESIS\n"
      for ((n, w) <- c.wires) {
        if (w.driveRand) {
          res += "    assign " + c.name + "." + n + " = " + emitRand(w) + ";\n"
        }
      }
      res += "  `endif\n"
    }
    res
  }

  override def emitDef(node: Node): String = {
    val res = 
    node match {
      case x: Bits =>
        if (x.isIo && x.dir == INPUT) {
          ""
        } else {
          if (node.inputs.length == 0) {
            ChiselError.warning("UNCONNECTED " + node + " IN " + node.component); ""
          } else if (node.inputs(0) == null) {
            ChiselError.warning("UNCONNECTED WIRE " + node + " IN " + node.component); ""
          } else {
            "  assign " + emitTmp(node) + " = " + emitRef(node.inputs(0)) + ";\n"
          }
        }

      case x: Mux =>
        "  assign " + emitTmp(x) + " = " + emitRef(x.inputs(0)) + " ? " + emitRef(x.inputs(1)) + " : " + emitRef(x.inputs(2)) + ";\n"

      case o: Op =>
        val c = o.component;
        "  assign " + emitTmp(o) + " = " +
        (if (o.op == "##") {
          "{" + emitRef(node.inputs(0)) + ", " + emitRef(node.inputs(1)) + "}"
        } else if (node.inputs.length == 1) {
          o.op + " " + emitRef(node.inputs(0))
        } else if (o.op == "s*s" || o.op == "s*u" || o.op == "s%s" || o.op == "s/s") {
          "$signed(" + emitRef(node.inputs(0)) + ") " + o.op(1) + " $signed(" + emitRef(node.inputs(1)) + ")"
        } else if (o.op == "s<" || o.op == "s<=") {
          "$signed(" + emitRef(node.inputs(0)) + ") " + o.op.tail + " $signed(" + emitRef(node.inputs(1)) + ")"
        } else if (o.op == "s>>") {
          "$signed(" + emitRef(node.inputs(0)) + ") >>> " + emitRef(node.inputs(1))
        } else {
          emitRef(node.inputs(0)) + " " + o.op + " " + emitRef(node.inputs(1))
        }) + ";\n"

      case x: Extract =>
        node.inputs.tail.foreach(x.validateIndex)
        if (node.inputs.length < 3) {
          if(node.inputs(0).width > 1) {
            "  assign " + emitTmp(node) + " = " + emitRef(node.inputs(0)) + "[" + emitRef(node.inputs(1)) + "];\n"
          } else {
            "  assign " + emitTmp(node) + " = " + emitRef(node.inputs(0)) + ";\n"
          }
        } else {
          if(node.inputs(0).width > 1) {
            "  assign " + emitTmp(node) + " = " + emitRef(node.inputs(0)) + "[" + emitRef(node.inputs(1)) + ":" + emitRef(node.inputs(2)) + "];\n"
          } else {
            "  assign " + emitTmp(node) + " = " + emitRef(node.inputs(0)) + ";\n"
          }
        }

      case m: Mem[_] =>
        if(!m.isInline) {
          val configStr =
          (" depth " + m.n +
            " width " + m.width +
            " ports " + m.ports.map(_.getPortType).reduceLeft(_ + "," + _) +
            "\n")
          val name = getMemName(m, configStr)
          ChiselError.info("MEM " + name)

          val clk = "    .CLK(" + emitRef(m.clock) + ")"
          val portdefs = for (i <- 0 until m.ports.size)
            yield emitPortDef(m.ports(i), i)
          "  " + name + " " + emitRef(m) + " (\n" +
            (clk +: portdefs).reduceLeft(_ + ",\n" + _) + "\n" +
          "  );\n"
        } else {
          ""
        }
      case m: MemRead =>
        if (m.mem.isInline) {
          "  assign " + emitTmp(node) + " = " + emitRef(m.mem) + "[" + emitRef(m.addr) + "];\n"
        } else {
          ""
        }

      case r: ROMRead =>
        val inits = new StringBuilder
        for ((i, v) <- r.rom.sparseLits)
          inits append s"    ${i}: ${emitRef(r)} = ${emitRef(v)};\n"
        s"  always @(*) case (${emitRef(r.inputs.head)})\n" +
        inits +
        "`ifndef SYNTHESIS\n" +
        s"    default: ${emitRef(r)} = ${emitRand(r)};\n" +
        "`else\n" +
        s"    default: ${emitRef(r)} = ${r.width}'bx;\n" +
        "`endif\n" +
        "  endcase\n"

      case s: Sprintf =>
        "  always @(*) $sformat(" + emitTmp(s) + ", " + s.args.map(emitRef _).foldLeft(CString(s.format))(_ + ", " + _) + ");\n"

      case _ =>
        ""
    }
    (if (node.prune && res != "") "//" else "") + res    
  }

  def emitDecBase(node: Node, wire: String = "wire"): String =
    s"  ${wire}${emitWidth(node)} ${emitRef(node)};\n"

  def emitDecReg(node: Node): String = emitDecBase(node, "reg ")

  override def emitDec(node: Node): String = {
    val res = 
    node match {
      case x: Bits =>
        if(!x.isIo) {
          emitDecBase(node)
        } else {
          ""
        }

      case _: Assert =>
        "  reg" + "[" + (node.width-1) + ":0] " + emitRef(node) + " = 1'b0;\n"

      case _: Reg =>
        emitDecReg(node)

      case _: Sprintf =>
        emitDecReg(node)

      case _: ROMRead =>
        emitDecReg(node)

      case m: Mem[_] =>
        if (m.isInline) {
          "  reg [" + (m.width-1) + ":0] " + emitRef(m) + " [" + (m.n-1) + ":0];\n"
        } else {
          ""
        }

      case x: MemAccess =>
        x.referenced = true
        emitDecBase(node)

      case _: ROMData => ""

      case _: Literal => ""

      case _ =>
        emitDecBase(node)
    }
    (if (node.prune && res != "") "//" else "") + res
  }

  def emitInit(node: Node): String = node match {
    case r: Reg =>
      "    " + emitRef(r) + " = " + emitRand(r) + ";\n"
    case m: Mem[_] =>
      if (m.isInline)
        "    for (initvar = 0; initvar < " + m.n + "; initvar = initvar+1)\n" +
        "      " + emitRef(m) + "[initvar] = " + emitRand(m) + ";\n"
      else
        ""
    case _ =>
      ""
  }

  def genHarness(c: Module, name: String) {
    val harness  = createOutputFile(name + "-harness.v");
    val printNodes = for ((n, io) <- c.io.flatten ; if io.dir == OUTPUT) yield io
    val scanNodes = for ((n, io) <- c.io.flatten ; if io.dir == INPUT) yield io
    val mainClk = Driver.implicitClock
    val clocks = LinkedHashSet(mainClk)
    clocks ++= c.clocks
    val (_, resets: ArrayBuffer[Bool]) = c.resets.unzip

    harness.write("module test;\n")
    for (node <- scanNodes) {
      harness.write("  reg [" + (node.width-1) + ":0] " + emitRef(node) + ";\n")
    }
    for (node <- printNodes) {
      harness.write("  wire [" + (node.width-1) + ":0] " + emitRef(node) + ";\n")
    }
    for (rst <- resets)
      harness.write("  reg %s = 1;\n".format(rst.name))

    // Diffent code generation for clocks
    if (Driver.isTesting) {
      harness.write("  reg %s = 1;\n".format(mainClk.name))
      if (clocks.size > 1) {
        for (clk <- clocks) {
          val clkLength = 
            if (clk.srcClock == null) "0" else 
            clk.srcClock.name + "_length " + clk.initStr
          harness.write("  integer %s_length = %s;\n".format(clk.name, clkLength))
          harness.write("  integer %s_cnt = 0;\n".format(clk.name))
          harness.write("  reg %s_fire = 0;\n".format(clk.name))
        }
      }

      harness.write("  always #100 %s = ~%s;\n\n".format(mainClk.name, mainClk.name))
    } else {
      for (clk <- clocks) {
        val clkLength = 
            if (clk.srcClock == null) "120" else 
            clk.srcClock.name + "_length " + clk.initStr
        harness.write("  reg %s = 0;\n".format(clk.name))
        harness.write("  parameter %s_length = %s;\n".format(clk.name, clkLength))
      }
      for (clk <- clocks) {
        harness.write("  always #%s_length %s = ~%s;\n".format(clk.name, clk.name, clk.name))
      }
    }

    if (Driver.isTesting) {
      harness.write("\n  /*** API variables ***/\n")
      harness.write("  reg[20*8:0] cmd;    // API command\n")    
      harness.write("  reg[1000*8:0] node; // Chisel node name;\n")
      harness.write("  integer value;      // 'poked' value\n")  
      harness.write("  integer offset;     // mem's offset\n")
      harness.write("  integer steps;      // number of steps\n")
      harness.write("  reg isStep = 0;\n\n")
    }

    harness.write("  /*** DUT instantiation ***/\n")
    harness.write("    " + c.moduleName + "\n")
    harness.write("      " + c.moduleName + "(\n")
    if (Driver.isTesting) {
      if (c.clocks.size == 1) {
        harness.write("        .%s(%s && isStep),\n".format(mainClk.name, mainClk.name))
      } else {
        for (clk <- c.clocks)
          harness.write("        .%s(%s && %s_fire && isStep),\n".format(
            clk.name, mainClk.name, clk.name)
         )
      }
    } else {
      for (clk <- c.clocks)
        harness.write("        .%s(%s),\n".format(clk.name, clk.name))
    }
    for (rst <- resets)
      harness.write("        .%s(%s),\n".format(rst.name, rst.name))
    var first = true
    for (node <- (scanNodes ++ printNodes))
      if(node.isIo && node.component == c) {
        if (first) {
          harness.write("        ." + emitRef(node) + "(" + emitRef(node) + ")")
          first = false
        } else {
          harness.write(",\n        ." + emitRef(node) + "(" + emitRef(node) + ")")
        }
      }
    harness.write("\n")
    harness.write(" );\n\n")

    val mems =  new ArrayBuffer[Mem[_]]
    val wires = new ArrayBuffer[Node]
    val dumpvars = new ArrayBuffer[Node]

    // select Chisel nodes for APIs(peek, poke)  and VCD dump
    for (m <- Driver.components ; mod <- m.mods) { 
      if (mod.isInObject && !mod.isLit) {
        mod match {
          case bool: Bool if resets contains bool => // exclude resets
          case _: Binding =>
          case _: ROMData =>
          case io: Bits if m != c => {
            var included = true
            if (io.dir == INPUT) {
              if (io.inputs.length == 0 || io.inputs.length > 1 || (m.isWalked contains io))
                included = false
            }
            else if (io.dir == OUTPUT) {
              if (io.consumers.length == 0 || m.parent.findBinding(io) == null || io.prune)
                included = false
            }
            if (included) wires += io
          }
          case mem:  Mem[_] =>  mems += mem
          case _ => wires += mod
        }
      }
      if (mod.isInVCD) {
        mod match {
          case io: Bits if m != c => {
            var included = true
            if (io.dir == INPUT) {
              if (io.inputs.length == 0 || io.inputs.length > 1 || (m.isWalked contains io))
                included = false
            }
            else if (io.dir == OUTPUT) {
              if (io.consumers.length == 0 || m.parent.findBinding(io) == null || io.prune)
                included = false
            }
            if (included) dumpvars += io
          }
          case _ => dumpvars += mod
        }
      }
    }
    
    harness.write("  /*** resets &&  VCD / VPD dumps ***/\n")
    harness.write("  initial begin\n")
    for (rst <- resets)
      harness.write("  %s = 1;\n".format(rst.name))
    if (Driver.isDebug) {
      harness.write("    /*** Debuggin with VPD dump ***/\n")
      harness.write("    $vcdplusfile(\"%s.vpd\");\n".format(ensureDir(Driver.targetDir)+c.name))
      harness.write("    $vcdpluson;\n")
    }
    harness.write("  #250;\n")
    for (rst <- resets)
      harness.write("  %s = 0;\n".format(rst.name))
    if (!Driver.isDebug && Driver.isVCD) {
      harness.write("    /*** VCD dump ***/\n")
      var first = true
      for (dumpvar <- dumpvars) {
        val pathName = dumpvar.component.getPathName(".") + "." + emitRef(dumpvar)
        harness.write("    $dumpvars(1, %s);\n".format(pathName))
      }
      harness.write("    $dumpfile(\"%s.vcd\");\n".format(ensureDir(Driver.targetDir)+c.name))
      harness.write("    $dumpon;\n")
    }
    harness.write("  end\n\n")

    harness.write("  /*** ROM & Mem initialization ***/\n")
    harness.write("  integer i = 0;\n")
    harness.write("  initial begin\n")
    harness.write("  #50;\n")
    for (mem <- mems) {
      val pathName = mem.component.getPathName(".") + "." + emitRef(mem)
      harness.write("    for (i = 0 ; i < %d ; i = i + 1) begin\n".format(mem.n))
      harness.write("      %s[i] = 0;\n".format(pathName))
      harness.write("    end\n")
    }
    harness.write("  end\n\n")

    // TODO: select interface according to the tester
    if (Driver.isTesting) { 
      harness write harnessAPIs(mainClk, clocks, resets, wires, mems, scanNodes, printNodes)
    } else {
      // for scripts: show the states
      harness write harnessMap(mainClk, resets, scanNodes, printNodes)
    }
    harness.write("endmodule\n")

    harness.close();
  }

  def harnessAPIs (mainClk: Clock, clocks: LinkedHashSet[Clock], resets: ArrayBuffer[Bool], 
                   wires: ArrayBuffer[Node], mems: ArrayBuffer[Mem[_]], 
                   scanNodes: Array[Bits], printNodes: Array[Bits]) = {
    val apis = new StringBuilder

    apis.append("  /*** Shadow declaration for 'peeking' ***/\n")
    val shadowNames = new HashMap[Node, String]
    apis.append("  // wire shadows\n")
    for (wire <- wires ; if !wire.isReg) {
      val shadowName = wire.component.getPathName("_") + "_" + emitRef(wire) + "_shadow"
      shadowNames(wire) = shadowName
      apis.append("  reg [%d:0] %s = 0;\n".format(wire.width-1, shadowName))
    }
    apis.append("  // mem shadows\n")
    for (mem <- mems) {
      val shadowName = mem.component.getPathName("_") + "_" + emitRef(mem) + "_shadow"
      shadowNames(mem) = shadowName
      apis.append("  reg [%d:0] %s [%d:0];\n".format(mem.width-1, shadowName, mem.n-1))
    }

    apis.append("\n  integer count;\n")

    def fscanf(form: String, args: String*) = 
      "count = $fscanf('h80000000, \"%s\", %s);\n".format(form, (args.tail foldLeft args.head) (_ + ", " + _))
    def display(form: String, args: String*) =
      "$display(\"%s\", %s);\n".format(form, (args.tail foldLeft args.head) (_ + ", " + _)) 

    apis.append("  always @(negedge %s) begin\n".format(mainClk.name))
    apis.append("  /*** API interpreter ***/\n")
    apis.append("  // process API command at every clock's negedge\n")
    apis.append("  // when the target is stalled\n")
    apis.append("  if (!isStep%s) begin\n".format(
      (resets foldLeft "")(_ + " && !" + _.name) +
      ( if (clocks.size > 1) 
         (clocks foldLeft "")(_ + " && " + _.name + "_cnt == 0")
        else "" ) )
    )
    apis.append("    "+ fscanf("%s", "cmd"))
    apis.append("    case (cmd)\n")

    apis.append("      // < reset >\n")
    apis.append("      // inputs: # cycles the reset consumes\n")
    apis.append("      // return: none\n")
    apis.append("      \"reset\": begin\n")
    apis.append("        " + fscanf("%d", "steps"))
    for (rst <- resets)
      apis.append("        %s = 1;\n".format(rst.name))
    apis.append("        isStep = 1;\n")
    apis.append("        " + display("%1d", "steps")) 
    apis.append("      end\n")

    apis.append("      // < wire_peek >\n")
    apis.append("      // inputs: wire's name\n")
    apis.append("      // return: wire's value from its shadow\n")
    apis.append("      \"wire_peek\": begin\n")
    apis.append("        " + fscanf("%s", "node")) 
    apis.append("        case (node)\n")
    if (!wires.isEmpty) {
      for (wire <- wires) {
        val pathName = wire.component.getPathName(".") + "." + emitRef(wire)
        val wireName = if (shadowNames contains wire) shadowNames(wire) else pathName
        apis.append("          \"%s\": ".format(pathName) + 
          display("0x%1x", wireName)
        )
      }
    }
    apis.append("          default: " + display("%s", "\"error\""))
    apis.append("        endcase\n")
    apis.append("      end\n")

    apis.append("      // < mem_peek >\n")
    apis.append("      // inputs: mem's name\n")
    apis.append("      // return: mem's value from its shadow\n")
    apis.append("      \"mem_peek\": begin\n")
    apis.append("        " + fscanf("%s %d", "node", "offset"))
    apis.append("        case (node)\n")
    if (!mems.isEmpty) {
      for (mem <- mems) {
        val pathName = mem.component.getPathName(".") + "." + emitRef(mem)
        apis.append("          \"%s\": ".format(pathName) + 
          display("0x%1x", "%s[%s]".format(pathName, "offset"))
        )
      }
    }
    apis.append("          default: " + display("%s", "\"error\""))
    apis.append("        endcase\n")
    apis.append("      end\n")

    apis.append("      // < wire_poke >\n")
    apis.append("      // inputs: wire's name\n")
    apis.append("      // return: \"ok\" or \"error\"\n")
    apis.append("      \"wire_poke\": begin\n")
    apis.append("        " + fscanf("%s 0x%x", "node", "value"))
    apis.append("        case (node)\n")
    if (!wires.isEmpty) {
      for (wire <- wires ; if wire.isReg || (scanNodes contains wire)) {
        val pathName = wire.component.getPathName(".") + "." + emitRef(wire)
        val wireName = if (scanNodes contains wire) emitRef(wire) else pathName
        apis.append("          \"%s\": begin\n".format(pathName))
        apis.append("            %s = %s;\n".format(wireName, "value"))
        apis.append("            " + display("%s", "\"ok\""))
        apis.append("          end\n")
      }
    }
    apis.append("          default: " + display("%s", "\"error\""))
    apis.append("        endcase\n")
    apis.append("      end\n")

    apis.append("      // < mem_poke >\n")
    apis.append("      // inputs: wire's name\n")
    apis.append("      // return: \"ok\" or \"error\"\n")
    apis.append("      \"mem_poke\": begin\n")
    apis.append("        " + fscanf("%s %d 0x%x", "node", "offset", "value")) 
    if (!mems.isEmpty) {
      apis.append("        case (node)\n")
      for (mem <- mems) {
        val pathName = mem.component.getPathName(".") + "." + emitRef(mem)
        apis.append("          \"%s\": begin\n".format(pathName))
        apis.append("            %s[%s] = %s;\n".format(pathName, "offset", "value"))
        apis.append("            " + display("%s", "\"ok\""))
        apis.append("          end\n")
      }
      apis.append("          default: " + display("%s", "\"error\""))
      apis.append("        endcase\n")
    }
    apis.append("      end\n")

    apis.append("      // < step > \n")
    apis.append("      // inputs: # cycles\n")
    apis.append("      // return: # cycles the target will proceed\n") 
    apis.append("      \"step\": begin\n")
    apis.append("        " + fscanf("%d", "steps"))
    apis.append("        isStep = 1;\n")
    apis.append("        " + display("%1d", "steps"))
    apis.append("      end\n")

    if (clocks.size > 1) {
      apis.append("      // <set_clocks> \n")
      apis.append("      // inputs: clocks' length\n")
      apis.append("      // return: \"ok\" or \"error\"\n")
      apis.append("      \"set_clocks\": begin\n")
      val clkFormat = ((clocks filter (_.srcClock == null)).toList map (x => "%x"))
      val clkFires  = ((clocks filter (_.srcClock == null)) map (_.name + "_length")).toList
      apis.append("        " + fscanf((clkFormat foldLeft "")(_ + " " + _), clkFires:_*) )
      apis.append("        " + display("%s", "\"ok\""))
      apis.append("      end\n")
    }

    apis.append("      // < quit>: finish simulation\n")
    apis.append("      \"quit\": $finish;\n")
    apis.append("      // default return: \"error\"\n")
    apis.append("      default: " + display("%s", "\"error\""))
    apis.append("    endcase\n")
    apis.append("    end\n\n")

    apis.append("    // decrement step counts\n")
    apis.append("    if (steps > 0%s) begin \n".format(
      if (clocks.size > 1) 
       (clocks foldLeft "")(_ + " && " + _.name + "_cnt == 0")
      else "" ) )
    apis.append("      steps = steps - 1;\n")
    if (clocks.size > 1) {
      for (clk <- clocks)
        apis.append("      %s_cnt = %s_length;\n".format(clk.name, clk.name))
    }
    apis.append("    end\n")
    apis.append("    // stall the target when step counts is zero\n")
    apis.append("    else if (isStep%s) begin \n". format(
      if (clocks.size > 1) 
       (clocks foldLeft "")(_ + " && " + _.name + "_cnt == 0")
      else "" ) )
    apis.append("      isStep = 0;\n")
    for (rst <- resets)
      apis.append("      %s = 0;\n".format(rst.name))
    if (clocks.size > 1) {
      for (clk <- clocks)
        apis.append("      %s_fire = 0;\n".format(clk.name))
    }
    apis.append("    end\n")

    apis.append("    if (count == -1) $finish(1);\n")
    apis.append("  end\n\n")

    apis.append("  integer min = (1 << 31 -1);\n")
    apis.append("  always @(posedge %s) begin\n".format(mainClk.name))
    if (clocks.size > 1) {
      apis.append("    // fire clocks according to their relative length\n")
      apis.append("    if (isStep%s) begin\n".format(
        (resets foldLeft "")(_ + " && !" + _.name)
      ) )
      for (clk <- clocks)
        apis.append("      if (%s_length < min) min = %s_cnt;\n".format(clk.name, clk.name))
      for (clk <- clocks)
        apis.append("      %s_cnt = %s_cnt - min;\n".format(clk.name, clk.name))
      for (clk <- clocks) {
        apis.append("      if (%s_cnt == 0) %s_fire = 1;\n".format(clk.name, clk.name))
        apis.append("      else %s_fire = 0;\n".format(clk.name))
      }
      apis.append("      if (!(%s)) begin\n".format(
        (clocks.tail foldLeft (clocks.head.name + "_cnt == 0"))(_ + " && " + _.name + "_cnt == 0")
      ) )
      for (clk <- clocks)
        apis.append("        if (%s_cnt == 0) %s_cnt = %s_length;\n".format(
          clk.name, clk.name, clk.name) )
      apis.append("      end\n")
      apis.append("    end\n")

      apis.append("    // hack to reset\n")
      apis.append("    else if (isStep%s) begin\n".format(
        if (resets.isEmpty) ""
        else ((resets.tail foldLeft (" && (" + resets.head.name))(_ + " || " + _.name)) + ")"
      ) )
      for (clk <- clocks) {
        apis.append("      %s_cnt = 0;\n".format(clk.name, clk.name))
        apis.append("      %s_fire = 1;\n".format(clk.name, clk.name))
      }
      apis.append("    end\n\n")
    }

    apis.append("     // copy wires' & mems' value into shadows for 'peeking'\n")
    apis.append("    if (%sisStep) begin\n".format(
      if (clocks.size > 1) 
         (clocks foldLeft "")(_ + _.name + "_fire && ")
      else "" )
    )
    for (wire <- wires ; if !wire.isReg) {
      val pathName = wire.component.getPathName(".") + "." + emitRef(wire)
      val wireName = if (printNodes contains wire) emitRef(wire) else pathName
      apis.append("      %s = %s;\n".format(shadowNames(wire), wireName))
    }
    apis.append("    end\n")
    apis.append("  end\n")
    
    apis.result
  }

  def harnessMap (mainClk: Clock, resets: ArrayBuffer[Bool], scanNodes: Array[Bits], printNodes: Array[Bits]) = {
    val map = new StringBuilder
    val printFormat = printNodes.map(a => a.chiselName + ": 0x%x, ").fold("")((y,z) => z + " " + y)
    val scanFormat = scanNodes.map(a => "%x").fold("")((y,z) => z + " " + y)

    if (Driver.isTesting) {
      map.append("  integer count;\n")
      map.append("  always @(negedge %s) begin\n".format(mainClk.name))
      map.append("  #50;\n")
      if (!resets.isEmpty)
        map.append("    if (%s)\n".format(
          (resets.tail foldLeft ("!" + resets.head.name))(_ + " || !" + _.name)))
      map.append("      count = $fscanf('h80000000, \"" + scanFormat.slice(0,scanFormat.length-1) + "\"")
      for (node <- scanNodes)
        map.append(", " + emitRef(node))
      map.append(");\n")
      map.append("      if (count == -1) $finish(1);\n")
      map.append("  end\n")
    }
    map.append("  always @(posedge %s) begin\n".format(mainClk.name))
    if (!resets.isEmpty)
      map.append("    if (%s)\n".format(
        (resets.tail foldLeft ("!" + resets.head.name))(_ + " || !" + _.name)))
    map.append("      $display(\"" + printFormat.slice(0,printFormat.length-1) + "\"")
    for (node <- printNodes) {
      map.append(", " + emitRef(node))
    }
    map.append(");\n")
    map.append("  end\n")

    map.result
  }

  def emitDefs(c: Module): StringBuilder = {
    val res = new StringBuilder()
    for (m <- c.mods) {
      res.append(emitDef(m))
    }
    for (c <- c.children) {
      res.append(emitDef(c))
    }
    res
  }

  def emitRegs(c: Module): StringBuilder = {
    val res = new StringBuilder();
    val clkDomains = new HashMap[Clock, StringBuilder]
    for (clock <- c.clocks) {
      clkDomains += (clock -> new StringBuilder)
    }
    for (p <- c.asserts) {
      clkDomains(p.clock).append(emitAssert(p))
    }
    for (m <- c.mods) {
      val clkDomain = clkDomains getOrElse (m.clock, null)
      if (m.clock != null && clkDomain != null)
        clkDomain.append(emitReg(m))
    }
    for (p <- c.printfs) {
      val clkDomain = clkDomains getOrElse (p.clock, null)
      if (p.clock != null && clkDomain != null)
        clkDomain.append(emitPrintf(p))
    }
    for (clock <- c.clocks) {
      val dom = clkDomains(clock)
      if (!dom.isEmpty) {
        if (res.isEmpty)
          res.append("\n")
        res.append("  always @(posedge " + emitRef(clock) + ") begin\n")
        res.append(dom.result())
        res.append("  end\n")
      }
    }
    res
  }

  def emitPrintf(p: Printf): String = {
    "`ifndef SYNTHESIS\n" +
    "`ifdef PRINTF_COND\n" +
    "    if (`PRINTF_COND)\n" +
    "`endif\n" +
    "      if (" + emitRef(p.cond) + ")\n" +
    "        $fwrite(32'h80000002, " + p.args.map(emitRef _).foldLeft(CString(p.format))(_ + ", " + _) + ");\n" +
    "`endif\n"
  }
  def emitAssert(a: Assert): String = {
    "`ifndef SYNTHESIS\n" +
    "  if(" + emitRef(a.reset) + ") " + emitRef(a) + " <= 1'b1;\n" +
    "  if(!" + emitRef(a.cond) + " && " + emitRef(a) +") begin\n" +
    "    $fwrite(32'h80000002, " + CString("ASSERTION FAILED: %s\n") + ", " + CString(a.message) + ");\n" +
    "    $finish;\n" +
    "  end\n" +
    "`endif\n"
  }

  def emitReg(node: Node): String = {
    node match {
      case reg: Reg =>
        def cond(c: Node) = "if(" + emitRef(c) + ") begin"
        def uncond = "begin"
        def sep = "\n      "
        def assign(r: Reg, x: Node) = emitRef(r) + " <= " + emitRef(x) + ";\n"
        def traverseMuxes(r: Reg, x: Node): List[String] = x match {
          case m: Mux => (cond(m.inputs(0)) + sep + assign(r, m.inputs(1))) :: traverseMuxes(r, m.inputs(2))
          case _ => if (x eq r) Nil else List(uncond + sep + assign(r, x))
        }
        if (!reg.next.isInstanceOf[Mux]) "    " + assign(reg, reg.next)
        else "    " + traverseMuxes(reg, reg.next).reduceLeft(_ + "    end else " + _) + "    end\n"

      case m: MemWrite =>
        if (m.mem.isInline) {
          "    if (" + emitRef(m.cond) + ")\n" +
          "      " + emitRef(m.mem) + "[" + emitRef(m.addr) + "] <= " + emitRef(m.data) + ";\n"
        } else {
          ""
        }
      case _ =>
        ""
    }
  }

  def emitDecs(c: Module): StringBuilder =
    c.mods.map(emitDec(_)).addString(new StringBuilder)

  def emitInits(c: Module): StringBuilder = {
    val sb = new StringBuilder
    c.mods.map(emitInit(_)).addString(sb)

    val res = new StringBuilder
    if (!sb.isEmpty) {
      res append "`ifndef SYNTHESIS\n"
      res append "  integer initvar;\n"
      res append "  initial begin\n"
      res append "    #0.002;\n"
      res append sb
      res append "  end\n"
      res append "`endif\n"
    }
    res
  }

  def emitModuleText(c: Module): String = {
    if (c.isInstanceOf[BlackBox])
      return ""

    val res = new StringBuilder()
    var first = true;
    var nl = "";
    if (c.clocks.length > 0 || c.resets.size > 0)
      res.append((c.clocks ++ c.resets.values.toList).map(x => "input " + emitRef(x)).reduceLeft(_ + ", " + _))
    val ports = new ArrayBuffer[StringBuilder]
    for ((n, w) <- c.wires) {
      // if(first && !hasReg) {first = false; nl = "\n"} else nl = ",\n";
      w match {
        case io: Bits => {
          val prune = if (io.prune && c != Driver.topComponent) "//" else ""
          if (io.dir == INPUT) {
            ports += new StringBuilder(nl + "    " + prune + "input " + 
                                       emitWidth(io) + " " + emitRef(io));
          } else if(io.dir == OUTPUT) {
            ports += new StringBuilder(nl + "    " + prune + "output" + 
                                       emitWidth(io) + " " + emitRef(io));
          }
        }
      };
    }
    val uncommentedPorts = ports.filter(!_.result.contains("//"))
    uncommentedPorts.slice(0, uncommentedPorts.length-1).map(_.append(","))
    if (c.clocks.length > 0 || c.resets.size > 0) res.append(",\n") else res.append("\n")
    res.append(ports.map(_.result).reduceLeft(_ + "\n" + _))
    res.append("\n);\n\n");
    // TODO: NOT SURE EXACTLY WHY I NEED TO PRECOMPUTE TMPS HERE
    for (m <- c.mods)
      emitTmp(m);
    res.append(emitDecs(c));
    res.append("\n");
    res.append(emitInits(c));
    res.append("\n");
    res.append(emitDefs(c));
    res.append(emitRegs(c))
    res.append("endmodule\n\n");
    res.result();
  }

  def flushModules( out: java.io.FileWriter,
    defs: LinkedHashMap[String, LinkedHashMap[String, ArrayBuffer[Module] ]],
    level: Int ) {
    for( (className, modules) <- defs ) {
      var index = 0
      for ( (text, comps) <- modules) {
        val moduleName = if( modules.size > 1 ) {
          className + "_" + index.toString;
        } else {
          className;
        }
        index = index + 1
        var textLevel = 0;
        for( flushComp <- comps ) {
          textLevel = flushComp.level;
          if( flushComp.level == level && flushComp.moduleName == "") {
            flushComp.moduleName = moduleName
          }
        }
        if( textLevel == level ) {
          /* XXX We write the module source text in *emitChildren* instead
                 of here so as to generate a minimal "diff -u" with the previous
                 implementation. */
        }
      }
    }
  }


  def emitChildren(top: Module,
    defs: LinkedHashMap[String, LinkedHashMap[String, ArrayBuffer[Module] ]],
    out: java.io.FileWriter, depth: Int) {
    if (top.isInstanceOf[BlackBox])
      return

    for (child <- top.children) {
      emitChildren(child, defs, out, depth + 1);
    }
    val className = extractClassName(top);
    for( (text, comps) <- defs(className)) {
      if( comps contains top ) {
        if( !(flushedTexts contains text) ) {
          out.append("module " + top.moduleName + "(")
          out.append(text);
          flushedTexts += text
        }
        return;
      }
    }
  }


  def doCompile(top: Module, out: java.io.FileWriter, depth: Int): Unit = {
    /* *defs* maps Mod classes to Mod instances through
       the generated text of their module.
       We use a LinkedHashMap such that later iteration is predictable. */
    val defs = LinkedHashMap[String, LinkedHashMap[String, ArrayBuffer[Module]]]()
    var level = 0;
    for (c <- Driver.sortedComps) {
      ChiselError.info(depthString(depth) + "COMPILING " + c
        + " " + c.children.length + " CHILDREN"
        + " (" + c.level + "," + c.traversal + ")");
      c.findConsumers();
      ChiselError.checkpoint()

      c.collectNodes(c);
      if( c.level > level ) {
        /* When a component instance instantiates different sets
         of sub-components based on its constructor parameters, the same
         Module class might appear with different level in the tree.
         We thus wait until the very end to generate module names.
         If that were not the case, we could flush modules as soon as
         the source text for all components at a certain level in the tree
         has been generated. */
        flushModules(out, defs, level);
        level = c.level
      }
      val res = emitModuleText(c);
      val className = extractClassName(c);
      if( !(defs contains className) ) {
        defs += (className -> LinkedHashMap[String, ArrayBuffer[Module] ]());
      }
      if( defs(className) contains res ) {
        /* We have already outputed the exact same source text */
        defs(className)(res) += c;
        ChiselError.info("\t" + defs(className)(res).length + " components");
      } else {
        defs(className) += (res -> ArrayBuffer[Module](c));
      }
    }
    flushModules(out, defs, level);
    emitChildren(top, defs, out, depth);
  }

  override def elaborate(c: Module) {
    super.elaborate(c)

    val out = createOutputFile(c.name + ".v")
    doCompile(c, out, 0)
    ChiselError.checkpoint()
    out.close()

    if (!memConfs.isEmpty) {
      val out_conf = createOutputFile(Driver.topComponent.name + ".conf")
      out_conf.write(getMemConfString);
      out_conf.close();
    }
    if (Driver.isGenHarness) {
      genHarness(c, c.name);
    }
  }

  override def compile(c: Module, flags: String) {

    def run(cmd: String) {
      val c = Process(cmd).!
      ChiselError.info(cmd + " RET " + c)
    }
    val dir = Driver.targetDir + "/"
    val src = dir + c.name + "-harness.v " + dir + c.name + ".v"
    val cmd = "vcs -full64 -quiet +vc +v2k -timescale=10ns/10ps " + src + " -o " + dir + c.name + 
              ( if (!Driver.isTesting) " -debug" /* for ucli scripts */
                else if (Driver.isDebug) " -debug_pp" /* for vpd dump */ 
                else "" ) 
    run(cmd)
  }
}

