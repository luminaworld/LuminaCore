package core.luminaworld.modules.RconMonitor

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.RemoteServerCommandEvent

class RconListener(private val module: RconMonitorModule) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRemoteCommand(event: RemoteServerCommandEvent) {
        if (!module.isEnabled) return
        if (module.config?.getBoolean("settings.notify-command", true) == false) return

        module.handleRconCommand(event.command)
    }
}
