package core.luminaworld.listener

import core.luminaworld.LuminaCore
import core.luminaworld.updater.UpdateChecker
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(private val plugin: LuminaCore) : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        
        // ส่งข้อความแจ้งเตือนเฉพาะ OP หรือคนที่มี permission luminacore.admin และมีอัปเดตใหม่
        if (UpdateChecker.hasUpdate && (player.isOp || player.hasPermission("luminacore.admin"))) {
            val currentVersion = plugin.description.version
            val latestVersion = UpdateChecker.latestVersion
            val downloadUrl = UpdateChecker.downloadUrl
            
            // ส่งข้อความแจ้งเตือนหลังเข้าเซิร์ฟเวอร์ 2 วินาที (40 ticks) เพื่อไม่ให้สับสนกับข้อความตอนเข้าเกมทั่วไป
            player.scheduler.runDelayed(plugin, { _ ->
                if (player.isOnline) {
                    val msg1 = plugin.getMsg("update-detected", "&7[&eLuminaCore&7] &aตรวจพบเวอร์ชันใหม่ล่าสุด! &e(%latest_version%)")
                        .replace("%latest_version%", latestVersion)
                    val msg2 = plugin.getMsg("update-current", "&7[&eLuminaCore&7] &7เวอร์ชันปัจจุบันของคุณคือ: &c(v%current_version%)")
                        .replace("%current_version%", currentVersion)
                    val msg3 = plugin.getMsg("update-download", "&7[&eLuminaCore&7] &aดาวน์โหลดเวอร์ชันใหม่ได้ที่: &b%url%")
                        .replace("%url%", downloadUrl)
                    
                    player.sendMessage(msg1)
                    player.sendMessage(msg2)
                    player.sendMessage(msg3)
                }
            }, null, 40L)

        }
    }
}
