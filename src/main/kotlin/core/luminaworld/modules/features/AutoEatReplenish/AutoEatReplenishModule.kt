package core.luminaworld.modules.features.AutoEatReplenish

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class AutoEatReplenishModule(plugin: LuminaCore) : LuminaModule(plugin, "AutoEatReplenish") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(AutoEatReplenishListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
