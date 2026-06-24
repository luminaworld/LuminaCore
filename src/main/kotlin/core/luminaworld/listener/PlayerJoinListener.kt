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
                    player.sendMessage("§7[§eLuminaCore§7] §aตรวจพบเวอร์ชันใหม่ล่าสุด! §e($latestVersion)")
                    player.sendMessage("§7[§eLuminaCore§7] §7เวอร์ชันปัจจุบันของคุณคือ: §c(v$currentVersion)")
                    player.sendMessage("§7[§eLuminaCore§7] §aดาวน์โหลดเวอร์ชันใหม่ได้ที่: §b$downloadUrl")
                }
            }, null, 40L)
        }
    }
}
