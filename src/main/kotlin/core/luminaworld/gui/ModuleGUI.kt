package core.luminaworld.gui

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class ModuleGUI(private val plugin: LuminaCore) : InventoryHolder, Listener {
    private var inv: Inventory? = null

    override fun getInventory(): Inventory {
        return inv ?: Bukkit.createInventory(this, 9, "LuminaCore Modules")
    }

    /**
     * เปิดหน้าต่างจัดการในเกมให้ผู้เล่น
     */
    fun openGUI(player: Player) {
        val modules = plugin.moduleManager?.modules ?: emptyList()
        var size = ((modules.size / 9) + 1) * 9
        if (size > 54) size = 54

        val title = plugin.getMsg("gui-title", "LuminaCore Modules")
        val inventory = Bukkit.createInventory(this, size, title)
        this.inv = inventory

        for ((index, module) in modules.withIndex()) {
            if (index >= size) break
            inventory.setItem(index, createModuleItem(module))
        }

        // รันคำสั่งเปิดใน Entity Scheduler ของผู้เล่นคนนั้น
        player.scheduler.execute(plugin, { player.openInventory(inventory) }, null, 0)
    }

    private fun createModuleItem(module: LuminaModule): ItemStack {
        val isEnabled = module.isEnabled
        val material = if (isEnabled) Material.LIME_DYE else Material.GRAY_DYE
        val item = ItemStack(material)
        val meta = item.itemMeta

        if (meta != null) {
            val nameColor = if (isEnabled) "§a§l" else "§c§l"
            meta.setDisplayName(nameColor + module.name)

            val loreList = ArrayList<String>()
            val statusStr = if (isEnabled) {
                plugin.getMsg("gui-status-enabled", "Status: Enabled")
            } else {
                plugin.getMsg("gui-status-disabled", "Status: Disabled")
            }
            val toggleTip = plugin.getMsg("gui-click-to-toggle", "Click to toggle status")

            loreList.add(statusStr)
            loreList.add(toggleTip)
            meta.lore = loreList
            item.itemMeta = meta
        }

        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.inventory.holder != this) return

        // ห้ามนำไอเทมออกจากช่อง
        event.isCancelled = true

        val slot = event.rawSlot
        val modules = plugin.moduleManager?.modules ?: emptyList()

        if (slot in modules.indices) {
            val module = modules[slot]
            val newStatus = !module.isEnabled

            // 1. เปลี่ยนสถานะและบันทึกลงในไฟล์ Config ย่อยของตัวเอง
            module.isEnabled = newStatus
            module.config?.set("settings.enabled", newStatus)

            // บันทึกไฟล์ config ย่อยของโมดูลแบบ Async
            Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
                try {
                    module.config?.save(module.configFile)
                } catch (e: Exception) {
                    plugin.logger.severe("Failed to save configuration for module ${module.name}: ${e.message}")
                }
            }

            // 2. เรียกใช้งาน / ปิดใช้งานระบบจริง
            try {
                if (newStatus) {
                    module.onEnable()
                    val msg = plugin.getMsg("gui-module-enabled", "%prefix% Module %module% has been enabled.")
                    player.sendMessage(msg.replace("%module%", module.name))
                } else {
                    module.onDisable()
                    val msg = plugin.getMsg("gui-module-disabled", "%prefix% Module %module% has been disabled.")
                    player.sendMessage(msg.replace("%module%", module.name))
                }
            } catch (e: Exception) {
                plugin.logger.severe("Error switching status of module ${module.name}: ${e.message}")
            }

            // เล่นเสียงและวาด GUI ใหม่
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            openGUI(player)
        }
    }
}
