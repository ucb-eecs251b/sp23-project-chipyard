package chipyard

import chisel3._
import chisel3.internal.sourceinfo.{SourceInfo}

import freechips.rocketchip.prci._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.devices.debug.{HasPeripheryDebug, HasPeripheryDebugModuleImp, ExportDebug, DebugModuleKey}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._

import boom.common.{BoomTile}


import testchipip.{DromajoHelper, CanHavePeripheryTLSerial, SerialTLKey}




//////////////////////////////////////////////////////////////////////////////


abstract class EmptySubsystem(implicit p: Parameters) extends LazyModule {
  println("EmptySubsystem")

  override val module: EmptySubsystemModuleImp[EmptySubsystem]
}

abstract class EmptySubsystemModuleImp[+L <: EmptySubsystem](_outer: L) extends LazyModuleImp(_outer) {
  val outer = _outer
  println("EmptySubsystemModuleImp")
}

trait InstantiatesRawTiles { this: EmptySubsystem => 
  val tileAttachParams: Seq[CanAttachTile] = p(TilesLocated(location)).sortBy(_.tileParams.hartId)
  val tileParams: Seq[TileParams] = tileAttachParams.map(_.tileParams)
  val tileCrossingTypes: Seq[ClockCrossingType] = tileAttachParams.map(_.crossingParams.crossingType)

  /** The actual list of instantiated tiles in this subsystem. */
  val tile_prci_domains: Seq[TilePRCIDomain[_]] = tileAttachParams.foldLeft(Seq[TilePRCIDomain[_]]()) {
    case (instantiated, params) => instantiated :+ params.instantiate(tileParams, instantiated)(p)
  }

  val tiles: Seq[BaseTile] = tile_prci_domains.map(_.tile.asInstanceOf[BaseTile])

  // Helper functions for accessing certain parameters that are popular to refer to in subsystem code
  def nTiles: Int = tileAttachParams.size
  def hartIdList: Seq[Int] = tileParams.map(_.hartId)
  def localIntCounts: Seq[Int] = tileParams.map(_.core.nLocalInterrupts)

  require(hartIdList.distinct.size == tiles.size, s"Every tile must be statically assigned a unique id, but got:\n${hartIdList}")
}


trait HasRawTileInputConstants extends InstantiatesRawTiles { this: EmptySubsystem =>
  /** tileHartIdNode is used to collect publishers and subscribers of hartids. */
  val tileHartIdNode = BundleBridgeEphemeralNode[UInt]()

  /** tileHartIdNexusNode is a BundleBridgeNexus that collects dynamic hart prefixes.
    *
    *   Each "prefix" input is actually the same full width as the outer hart id; the expected usage
    *   is that each prefix source would set only some non-overlapping portion of the bits to non-zero values.
    *   This node orReduces them, and further combines the reduction with the static ids assigned to each tile,
    *   producing a unique, dynamic hart id for each tile.
    *
    *   If p(InsertTimingClosureRegistersOnHartIds) is set, the input and output values are registered.
    *
    *   The output values are [[dontTouch]]'d to prevent constant propagation from pulling the values into
    *   the tiles if they are constant, which would ruin deduplication of tiles that are otherwise homogeneous.
    */
  val tileHartIdNexusNode = LazyModule(new BundleBridgeNexus[UInt](
    inputFn = BundleBridgeNexus.orReduction[UInt](registered = p(InsertTimingClosureRegistersOnHartIds)) _,
    outputFn = (prefix: UInt, n: Int) =>  Seq.tabulate(n) { i =>
      val y = dontTouch(prefix | hartIdList(i).U(p(MaxHartIdBits).W)) // dontTouch to keep constant prop from breaking tile dedup
      if (p(InsertTimingClosureRegistersOnHartIds)) BundleBridgeNexus.safeRegNext(y) else y
    },
    default = Some(() => 0.U(p(MaxHartIdBits).W)),
    inputRequiresOutput = true, // guard against this being driven but then ignored in tileHartIdIONodes below
    shouldBeInlined = false // can't inline something whose output we are are dontTouching
  )).node
  // TODO: Replace the DebugModuleHartSelFuncs config key with logic to consume the dynamic hart IDs

  /** tileResetVectorNode is used to collect publishers and subscribers of tile reset vector addresses. */
  val tileResetVectorNode = BundleBridgeEphemeralNode[UInt]()

  /** tileResetVectorNexusNode is a BundleBridgeNexus that accepts a single reset vector source, and broadcasts it to all tiles. */
  val tileResetVectorNexusNode = BundleBroadcast[UInt](
    inputRequiresOutput = true // guard against this being driven but ignored in tileResetVectorIONodes below
  )

  /** tileHartIdIONodes may generate subsystem IOs, one per tile, allowing the parent to assign unique hart ids.
    *
    *   Or, if such IOs are not configured to exist, tileHartIdNexusNode is used to supply an id to each tile.
    */
  val tileHartIdIONodes: Seq[BundleBridgeSource[UInt]] = p(SubsystemExternalHartIdWidthKey) match {
    case Some(w) => Seq.fill(tiles.size) {
      val hartIdSource = BundleBridgeSource(() => UInt(w.W))
      tileHartIdNode := hartIdSource
      hartIdSource
    }
    case None => { tileHartIdNode :*= tileHartIdNexusNode; Nil }
  }

  /** tileResetVectorIONodes may generate subsystem IOs, one per tile, allowing the parent to assign unique reset vectors.
    *
    *   Or, if such IOs are not configured to exist, tileResetVectorNexusNode is used to supply a single reset vector to every tile.
    */
  val tileResetVectorIONodes: Seq[BundleBridgeSource[UInt]] = p(SubsystemExternalResetVectorKey) match {
    case true => Seq.fill(tiles.size) {
      val resetVectorSource = BundleBridgeSource[UInt]()
      tileResetVectorNode := resetVectorSource
      resetVectorSource
    }
    case false => { tileResetVectorNode :*= tileResetVectorNexusNode; Nil }
  }
}


trait HasRawTiles extends InstantiatesRawTiles with HasCoreMonitorBundles
{ this: EmptySubsystem =>
  implicit val p: Parameters

}

  // connect all the tiles to interconnect attachment points made available in this subsystem context
// tileAttachParams.zip(tile_prci_domains).foreach { case (params, td) =>
// params.connect(td.asInstanceOf[TilePRCIDomain[params.TileType]], this.asInstanceOf[params.TileContextType])
// }
}

trait HasRawTilesModuleImp extends LazyModuleImp {
  val outer: HasRawTiles with HasRawTileInputConstants

  val reset_vector = outer.tileResetVectorIONodes.zipWithIndex.map { case (n, i) => n.makeIO(s"reset_vector_$i") }
  val tile_hartids = outer.tileHartIdIONodes.zipWithIndex.map { case (n, i) => n.makeIO(s"tile_hartids_$i") }

// val meip = if(outer.meipNode.isDefined) Some(IO(Input(Vec(outer.meipNode.get.out.size, Bool())))) else None
// meip.foreach { m =>
// m.zipWithIndex.foreach{ case (pin, i) =>
// (outer.meipNode.get.out(i)._1)(0) := pin
// }
// }
// val seip = if(outer.seipNode.isDefined) Some(IO(Input(Vec(outer.seipNode.get.out.size, Bool())))) else None
// seip.foreach { s =>
// s.zipWithIndex.foreach{ case (pin, i) =>
// (outer.seipNode.get.out(i)._1)(0) := pin
// }
// }
// val nmi = outer.tiles.zip(outer.tileNMIIONodes).zipWithIndex.map { case ((tile, n), i) => tile.tileParams.core.useNMI.option(n.makeIO(s"nmi_$i")) }
}


class TileOnlySubsystem(implicit p: Parameters) extends EmptySubsystem with HasRawTiles {
  def coreMonitorBundles = tiles.map {
    case r: RocketTile => r.module.core.rocketImpl.coreMonitorBundle
    case b: BoomTile => b.module.core.coreMonitorBundle
  }.toList
}

class TileOnlySubsystemModuleImp[+L <: TileOnlySubsystem](_outer: L) extends EmptySubsystemModuleImp(_outer) 
  with HasRawTilesModuleImp
{
}
