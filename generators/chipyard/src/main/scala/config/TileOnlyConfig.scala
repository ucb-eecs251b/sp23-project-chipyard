package chipyard.config

import chisel3.util._
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._

// --------------
// Chipyard abstract ("base") configuration
// NOTE: This configuration is NOT INSTANTIABLE, as it defines a empty system with no tiles
//
// The default set of IOBinders instantiate IOcells and ChipTop IOs for digital IO bundles.
// The default set of HarnessBinders instantiate TestHarness hardware for interacting with ChipTop IOs
// --------------


class BaseTilePlaceholderConfig extends Config ((site, here, up) => {
  // Tile parameters
  case PgLevels => if (site(XLen) == 64) 3 /* Sv39 */ else 2 /* Sv32 */
  case XLen => 64 // Applies to all cores
  case MaxHartIdBits => log2Up((site(TilesLocated(InSubsystem)).map(_.tileParams.hartId) :+ 0).max+1)

  // Additional device Parameters
// case BootROMLocated(InSubsystem) => Some(BootROMParams(contentFileName = "./bootrom/bootrom.img"))
// case SubsystemExternalResetVectorKey => false
// case DebugModuleKey => Some(DefaultDebugModuleParams(site(XLen)))
// case CLINTKey => Some(CLINTParams())
// case PLICKey => Some(PLICParams())
  case TilesLocated(InSubsystem) => Nil
})

class AbstractTileOnlyConfig extends Config(
  new chipyard.config.BaseTilePlaceholderConfig
)

class RocketTileOnlyConfig extends Config(
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  new chipyard.config.AbstractTileOnlyConfig)
