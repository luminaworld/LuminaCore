package core.luminaworld.modules.features.ConfirmDrop

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class ConfirmDropModule(plugin: LuminaCore) : LuminaModule(plugin, "ConfirmDrop") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(ConfirmDropListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
