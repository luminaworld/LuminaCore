package core.luminaworld.modules.features.FarmlandConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class FarmlandConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "FarmlandConverter") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(FarmlandConverterListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
