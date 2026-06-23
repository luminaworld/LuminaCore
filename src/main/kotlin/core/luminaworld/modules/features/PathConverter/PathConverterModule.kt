package core.luminaworld.modules.features.PathConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class PathConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "PathConverter") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(PathConverterListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
