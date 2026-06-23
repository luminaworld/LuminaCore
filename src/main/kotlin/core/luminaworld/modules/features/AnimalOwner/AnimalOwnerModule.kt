package core.luminaworld.modules.features.AnimalOwner

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class AnimalOwnerModule(plugin: LuminaCore) : LuminaModule(plugin, "AnimalOwner") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(AnimalOwnerListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
