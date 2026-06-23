package core.luminaworld.modules.RconMonitor

import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.filter.AbstractFilter

class RconLogFilter(private val module: RconMonitorModule) : AbstractFilter() {
    override fun filter(event: LogEvent): Filter.Result {
        if (!module.isEnabled) return Filter.Result.NEUTRAL

        val message = event.message.formattedMessage

        // ตรวจจับข้อความ "RCON connection from: /IP"
        if (message.contains("RCON connection from:")) {
            try {
                var ip = "Unknown"
                val index = message.indexOf('/')
                if (index != -1) {
                    ip = message.substring(index + 1).trim()
                }
                
                module.handleRconLogin(ip)
            } catch (e: Exception) {
                module.plugin.logger.warning("Error parsing RCON connection log: ${e.message}")
            }
        }
        return Filter.Result.NEUTRAL
    }
}
