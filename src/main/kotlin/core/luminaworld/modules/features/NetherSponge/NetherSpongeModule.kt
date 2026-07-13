package core.luminaworld.modules.features.NetherSponge

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class NetherSpongeModule(plugin: LuminaCore) : LuminaModule(plugin, "NetherSponge") {
    private var listener: NetherSpongeListener? = null

    override fun onEnable() {
        listener = NetherSpongeListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบช่วยแปลงฟองน้ำเปียกเป็นฟองน้ำแห้งอัตโนมัติเมื่อโยนลงในนรก (Nether)") ?: "ระบบช่วยแปลงฟองน้ำเปียกเป็นฟองน้ำแห้งอัตโนมัติเมื่อโยนลงในนรก (Nether)"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบอบฟองน้ำในนรก (NetherSponge)",
                material = org.bukkit.Material.SPONGE,
                description = desc
            )
        )
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null

        core.luminaworld.settings.PlayerSettingsManager.unregisterSettingsOfModule(name)
    }
}
