package core.luminaworld.modules.features.AutoReplenish

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class AutoReplenishModule(plugin: LuminaCore) : LuminaModule(plugin, "AutoReplenish") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(AutoReplenishListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
