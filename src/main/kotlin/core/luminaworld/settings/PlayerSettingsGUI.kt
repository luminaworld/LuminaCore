package core.luminaworld.settings

import core.luminaworld.LuminaCore
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerSettingsGUI(private val plugin: LuminaCore) : InventoryHolder, Listener {
    private var inv: Inventory? = null
    
    // เก็บหน้าปัจจุบันของผู้เล่นเพื่อเปิดหน้าเดิมหรือคำนวณสลับหน้าอย่างถูกต้อง
    private val playerPages = ConcurrentHashMap<UUID, Int>()

    override fun getInventory(): Inventory {
        return inv ?: Bukkit.createInventory(this, 45, "§8§lตั้งค่าส่วนตัว (Settings)")
    }

    /**
     * เปิดหน้าตั้งค่าผู้เล่น
     */
    fun openGUI(player: Player, page: Int = 0) {
        val uuid = player.uniqueId
        playerPages[uuid] = page
        
        val title = "§8§lตั้งค่าส่วนตัว (หน้า ${page + 1})"
        val inventory = Bukkit.createInventory(this, 45, title)
        this.inv = inventory

        // เติมกระจกสีเทาตกแต่งทุกช่องว่างก่อน
        val glass = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val glassMeta = glass.itemMeta
        glassMeta?.setDisplayName(" ")
        glass.itemMeta = glassMeta
        for (i in 0 until 45) {
            inventory.setItem(i, glass)
        }

        // ดึงรายการตั้งค่าทั้งหมดที่ลงทะเบียนในระบบ
        val settings = PlayerSettingsManager.registeredSettings
        val startIndex = page * 36
        val endIndex = Math.min(startIndex + 36, settings.size)

        var slot = 0
        for (i in startIndex until endIndex) {
            val option = settings[i]
            inventory.setItem(slot, createSettingItem(player, option))
            slot++
        }

        // --- แถวควบคุมล่างสุด (ช่อง 36 ถึง 44) ---

        // ปุ่มหน้าก่อนหน้า (ย้อนกลับ) - แสดงเมื่อไม่อยู่หน้าแรก
        if (page > 0) {
            val prevBtn = ItemStack(Material.ARROW)
            val prevMeta = prevBtn.itemMeta
            prevMeta?.setDisplayName("§a§l⬅️ หน้าก่อนหน้า")
            val lore = ArrayList<String>()
            lore.add("§7คลิกย้อนกลับไปหน้า ${page}")
            prevMeta?.lore = lore
            prevBtn.itemMeta = prevMeta
            inventory.setItem(36, prevBtn)
        }

        // ปุ่มปิดหน้าต่าง - จัดให้อยู่กึ่งกลางแถว (ช่องที่ 40)
        val closeBtn = ItemStack(Material.BARRIER)
        val closeMeta = closeBtn.itemMeta
        closeMeta?.setDisplayName("§c§l❌ ปิดหน้าต่าง")
        val closeLore = ArrayList<String>()
        closeLore.add("§7คลิกเพื่อปิดหน้าต่างนี้")
        closeMeta?.lore = closeLore
        closeBtn.itemMeta = closeMeta
        inventory.setItem(40, closeBtn)

        // ปุ่มหน้าถัดไป - แสดงเมื่อยังมีข้อมูลในหน้าถัดไป
        if (endIndex < settings.size) {
            val nextBtn = ItemStack(Material.ARROW)
            val nextMeta = nextBtn.itemMeta
            nextMeta?.setDisplayName("§a§lหน้าถัดไป ➡️")
            val lore = ArrayList<String>()
            lore.add("§7คลิกเพื่อดูหน้าถัดไป (${page + 2})")
            nextMeta?.lore = lore
            nextBtn.itemMeta = nextMeta
            inventory.setItem(44, nextBtn)
        }

        player.scheduler.execute(plugin, { player.openInventory(inventory) }, null, 0)
    }

    /**
     * สร้างไอเทมปุ่มตั้งค่าของโมดูลย่อย
     */
    private fun createSettingItem(player: Player, option: PlayerSettingOption): ItemStack {
        val isEnabled = PlayerSettingsManager.isSettingEnabled(player, option.key)
        
        // หากผู้เล่นปิดระบบนั้นๆ ในตั้งค่า เราจะเปลี่ยนไอเทมแสดงผลเป็น GRAY_DYE เสมอ เพื่อแสดงสถานะปิดใช้งานชัดเจน
        val material = if (isEnabled) option.material else Material.GRAY_DYE
        val item = ItemStack(material)
        val meta = item.itemMeta

        if (meta != null) {
            val statusColor = if (isEnabled) "§a§l" else "§c§l"
            meta.setDisplayName("§eระบบ: $statusColor${option.displayName}")

            val loreList = ArrayList<String>()
            loreList.add("§7${option.description}")
            loreList.add("")
            
            val statusStr = if (isEnabled) "§7สถานะ: §a§lเปิดใช้งาน (Enabled)" else "§7สถานะ: §c§lปิดใช้งาน (Disabled)"
            loreList.add(statusStr)
            loreList.add("")
            loreList.add("§eคลิกเพื่อสลับสถานะในฝั่งของคุณ")
            
            meta.lore = loreList
            item.itemMeta = meta
        }
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.inventory.holder != this) return

        event.isCancelled = true

        val slot = event.rawSlot
        val uuid = player.uniqueId
        val currentPage = playerPages[uuid] ?: 0

        // 1. ปุ่มปิดหน้าต่าง (ช่อง 40)
        if (slot == 40) {
            player.scheduler.execute(plugin, { player.closeInventory() }, null, 0)
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            return
        }

        // 2. ปุ่มหน้าก่อนหน้า (ช่อง 36)
        if (slot == 36 && currentPage > 0) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            openGUI(player, currentPage - 1)
            return
        }

        // 3. ปุ่มหน้าถัดไป (ช่อง 44)
        val settings = PlayerSettingsManager.registeredSettings
        val nextStartIndex = (currentPage + 1) * 36
        if (slot == 44 && nextStartIndex < settings.size) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            openGUI(player, currentPage + 1)
            return
        }

        // 4. คลิกเพื่อตั้งค่าสถานะสลับปิด/เปิด (ช่อง 0 ถึง 35)
        if (slot in 0..35) {
            val targetIndex = currentPage * 36 + slot
            if (targetIndex in settings.indices) {
                val option = settings[targetIndex]
                val currentStatus = PlayerSettingsManager.isSettingEnabled(player, option.key)
                val newStatus = !currentStatus

                PlayerSettingsManager.setSettingEnabled(player, option.key, newStatus)

                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
                event.inventory.setItem(slot, createSettingItem(player, option))

                val statusStr = if (newStatus) "§aเปิดใช้งาน" else "§cปิดใช้งาน"
                player.sendMessage("§6[Settings] §fคุณได้ $statusStr §e${option.displayName} §fสำหรับตัวคุณแล้ว")
            }
        }
    }
}
