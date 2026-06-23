package core.luminaworld.modules.features.ConcreteConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class ConcreteConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "ConcreteConverter") {
    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(ConcreteConverterListener(plugin, this), plugin)
    }

    override fun onDisable() {}
}
