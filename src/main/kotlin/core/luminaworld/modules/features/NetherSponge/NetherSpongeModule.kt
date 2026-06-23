package core.luminaworld.modules.features.NetherSponge

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class NetherSpongeModule(plugin: LuminaCore) : LuminaModule(plugin, "NetherSponge") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(NetherSpongeListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
