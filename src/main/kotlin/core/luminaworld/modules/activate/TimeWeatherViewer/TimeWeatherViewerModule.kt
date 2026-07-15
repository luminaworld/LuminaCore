package core.luminaworld.modules.activate.TimeWeatherViewer

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList

class TimeWeatherViewerModule(plugin: LuminaCore) : LuminaModule(plugin, "TimeWeatherViewer") {

    private var listener: TimeWeatherViewerListener? = null

    override fun onEnable() {
        listener = TimeWeatherViewerListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบแสดงเวลาและสภาพอากาศปัจจุบันของโลก") ?: "ระบบแสดงเวลาและสภาพอากาศปัจจุบันของโลก"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบดูเวลาและสภาพอากาศ (TimeWeatherViewer)",
                material = org.bukkit.Material.CLOCK,
                description = desc
            )
        )
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null

        core.luminaworld.settings.PlayerSettingsManager.unregisterSettingsOfModule(name)
    }

    override fun onSneakTrigger(player: Player) {
        showTimeAndWeather(player)
    }

    fun showTimeAndWeather(player: Player) {
        if (!core.luminaworld.settings.PlayerSettingsManager.isSettingEnabled(player, name)) return
        val world = player.world
        val ticks = world.time
        
        // คำนวณชั่วโมงและนาที ( ticks ในเกม 1 วันมี 24000 ticks เริ่มที่ 06:00 )
        val hours = ((ticks / 1000) + 6) % 24
        val minutes = (ticks % 1000) * 60 / 1000
        val timeString = String.format("%02d:%02d", hours, minutes)

        // แปลงผลสภาพอากาศ
        val weatherKey = when {
            world.isThundering -> "messages.weather-thunder"
            world.hasStorm() -> "messages.weather-rain"
            else -> "messages.weather-clear"
        }
        val defaultWeatherStr = when {
            world.isThundering -> "Thunder"
            world.hasStorm() -> "Rain"
            else -> "Clear"
        }
        val weatherString = config?.getString(weatherKey, defaultWeatherStr) ?: defaultWeatherStr

        val msgTemplate = config?.getString("messages.info", "%prefix% &eTime: &a%time% &7| &eWeather: &a%weather%") ?: ""
        val finalMsg = msgTemplate
            .replace("%time%", timeString)
            .replace("%weather%", weatherString)

        sendNotification(player, finalMsg)
    }
}
