package core.luminaworld.modules.features.ColorSign

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class ColorSignModule(plugin: LuminaCore) : LuminaModule(plugin, "ColorSign") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(ColorSignListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
