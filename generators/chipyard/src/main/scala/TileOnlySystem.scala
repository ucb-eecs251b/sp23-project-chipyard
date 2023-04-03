package chipyard

import chisel3._

import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.{DontTouch}
import freechips.rocketchip.util._

import barstools.iocell.chisel._



///////////////////////////////////////////////////////////////////////////////


class TileOnlySystem(implicit p: Parameters) extends TileOnlySubsystem
{
  override lazy val module = new TileOnlySystemModule(this)

  val master_ios = tiles.zipWithIndes.map { case (t, i) =>
    InModuleBody {
      t.masterNode.outward.makeIO(s"tile_master_${i}")
    }
  }
    
}

class TileOnlySystemModule[+L <: TileOnlySystem](_outer: L) extends TileOnlySubsystem(_outer: L)
  with DontTouch {
}




///////////////////////////////////////////////////////////////////////////////



class TileOnlyDigitalTop(implicit p: Parameters) extends TileOnlySystem
// with ???
{
  override lazy val module = new TileOnlyDigitalTopModule(this)
}

class TileOnlyDigitalTopModule[+L <: TileOnlyDigitalTop](l: L) extends TileOnlySystemModule(l)
  with freechips.rocketchip.util.DontTouch



///////////////////////////////////////////////////////////////////////////////


case object BuildTileOnlySystem extends Field[Parameters => LazyModule]((p: Parameters) => new TileOnlyDigitalTop()(p))


class TileOnlyChipTop(implicit p: Parameters) extends LazyModule with BindingScope
    with HasIOBinders {

  lazy val lazyTileOnlySystem = LazyModule(BuildTileOnlySystem(p)).suggestName("tileOnlySystem")

  lazy val module: LazyModuleImpLike = new LazyRawModuleImp(this) { }
}
