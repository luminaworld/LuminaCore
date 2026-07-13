package core.luminaworld.modules.activate.GlowingTwerk

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GlowingTwerkModule(plugin: LuminaCore) : LuminaModule(plugin, "GlowingTwerk") {
    private var listener: GlowingTwerkListener? = null
    val cooldowns = ConcurrentHashMap<UUID, Long>()

    override fun onEnable() {
        listener = GlowingTwerkListener(this)
        listener?.let {
            plugin.server.pluginManager.registerEvents(it, plugin)
        }

        val desc = config?.getString("settings.description", "ระบบย่อตัวเพื่อเร่งการเจริญเติบโตของพืช") ?: "ระบบย่อตัวเพื่อเร่งการเจริญเติบโตของพืช"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบย่อปุ๋ยเร่งพืช (GlowingTwerk)",
                material = org.bukkit.Material.BONE_MEAL,
                description = desc
            )
        )
    }

    override fun onDisable() {
        listener?.let {
            HandlerList.unregisterAll(it)
            listener = null
        }
        cooldowns.clear()

        core.luminaworld.settings.PlayerSettingsManager.unregisterSettingsOfModule(name)
    }
}
