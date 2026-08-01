package core.luminaworld.modules.minigames.CaptureTheFlag

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.configuration.file.YamlConfiguration
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.input.DialogInput
import java.util.UUID

class CTFGUIHolder(val zoneName: String?, val pageType: PageType) : InventoryHolder {
    private var inv: Inventory? = null

    fun setInventory(inventory: Inventory) {
        this.inv = inventory
    }

    override fun getInventory(): Inventory {
        return inv ?: Bukkit.createInventory(null, 54)
    }
}

enum class PageType {
    LIST,
    EDITOR
}

class CaptureTheFlagGUI(private val module: CaptureTheFlagModule) : Listener {

    init {
        Bukkit.getPluginManager().registerEvents(this, module.plugin)
    }

    /**
     * เปิดเมนูหลัก แสดงรายการพื้นที่ยึดครองทั้งหมด
     */
    fun openMainGUI(player: Player) {
        val config = module.messagesConfig
        val titleStr = config?.getString("gui.list.title", "§8จัดการพื้นที่ยึดครอง (CTF)") ?: "§8จัดการพื้นที่ยึดครอง (CTF)"

        val holder = CTFGUIHolder(null, PageType.LIST)
        val inv = Bukkit.createInventory(holder, 54, module.parseToComponent(titleStr))
        holder.setInventory(inv)

        // ใส่ไอเทมตกแต่งขอบ
        val glassMatStr = config?.getString("gui.editor.decor-material", "GRAY_STAINED_GLASS_PANE") ?: "GRAY_STAINED_GLASS_PANE"
        val glassMat = Material.matchMaterial(glassMatStr) ?: Material.GRAY_STAINED_GLASS_PANE
        val decor = createDecorationItem(glassMat, " ")
        for (i in 0..8) inv.setItem(i, decor)
        for (i in 45..53) inv.setItem(i, decor)

        // แสดงผลรายการโซนยึด
        val zonesList = module.zones.values.toList()
        for (i in 0 until minOf(zonesList.size, 36)) {
            val zone = zonesList[i]
            inv.setItem(9 + i, createZoneListItem(zone))
        }

        // ปุ่มสร้างโซนใหม่
        val createSlot = config?.getInt("gui.list.create-button.slot", 49) ?: 49
        inv.setItem(createSlot, createListItem("gui.list.create-button"))

        // ปุ่มปิด
        val closeSlot = config?.getInt("gui.list.close-button.slot", 53) ?: 53
        inv.setItem(closeSlot, createListItem("gui.list.close-button"))

        player.scheduler.execute(module.plugin, { player.openInventory(inv) }, null, 0)
    }

    /**
     * เปิดเมนูเครื่องมือตั้งค่ารายพื้นที่
     */
    fun openEditorGUI(player: Player, zoneName: String) {
        val zone = module.getZone(zoneName) ?: return
        val config = module.messagesConfig
        val titleTemplate = config?.getString("gui.editor.title", "&8» จัดการโซน: &l%zone% &8«") ?: "&8» จัดการโซน: &l%zone% &8«"
        val title = titleTemplate.replace("%zone%", zone.name)

        val holder = CTFGUIHolder(zoneName, PageType.EDITOR)
        val inv = Bukkit.createInventory(holder, 54, module.parseToComponent(title))
        holder.setInventory(inv)

        // ตกแต่งพื้นหลังช่องว่างทั้งหมด
        val glassMatStr = config?.getString("gui.editor.decor-material", "GRAY_STAINED_GLASS_PANE") ?: "GRAY_STAINED_GLASS_PANE"
        val glassMat = Material.matchMaterial(glassMatStr) ?: Material.GRAY_STAINED_GLASS_PANE
        val decor = createDecorationItem(glassMat, " ")
        for (i in 0..53) {
            inv.setItem(i, decor)
        }

        // วาดสล็อตปุ่มต่างๆ ที่ตั้งค่าไว้ใน messages.yml
        val itemsSection = config?.getConfigurationSection("gui.editor.items")
        if (itemsSection != null) {
            for (key in itemsSection.getKeys(false)) {
                val slot = itemsSection.getInt("$key.slot", -1)
                if (slot in 0..53) {
                    val valueStr = getZoneValueString(zone, key)
                    inv.setItem(slot, createEditorItem(key, zone, valueStr))
                }
            }
        }

        player.scheduler.execute(module.plugin, { player.openInventory(inv) }, null, 0)
    }

    private fun getZoneValueString(zone: CaptureZone, key: String): String {
        return when (key) {
            "capture-time" -> zone.captureTimeSeconds.toString()
            "grace-period" -> zone.gracePeriodSeconds.toString()
            "cooldown-time" -> zone.cooldownTimeSeconds.toString()
            "min-y" -> zone.minY.toString()
            "max-y" -> zone.maxY.toString()
            "display-y" -> zone.displayY.toString()
            "particle-type" -> zone.particleType
            "particle-color" -> zone.particleColorHex
            "particle-size" -> zone.particleSize.toString()
            "height-offset" -> zone.particleHeightOffset.toString()
            "spawn-interval" -> zone.particleSpawnInterval.toString()
            "duration" -> zone.particleDuration.toString()
            "cooldown-type" -> zone.cooldownParticleType
            "cooldown-color" -> zone.cooldownParticleColorHex
            "cooldown-size" -> zone.cooldownParticleSize.toString()
            "particle-density" -> zone.particleDensity.toString()
            "toggle-particles" -> if (zone.particleEnabled) "&aเปิดใช้งาน" else "&cปิดใช้งาน"
            "hide-cooldown" -> if (zone.hideDuringCooldown) "&aเปิด (ซ่อน)" else "&cปิด (แสดง)"
            else -> ""
        }
    }

    private fun createListItem(path: String): ItemStack {
        val config = module.messagesConfig ?: return ItemStack(Material.STONE)
        val matStr = config.getString("$path.material", "STONE") ?: "STONE"
        val mat = Material.matchMaterial(matStr) ?: Material.STONE
        val name = config.getString("$path.name", "") ?: ""
        val lore = config.getStringList("$path.lore")

        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item
        meta.displayName(module.parseToComponent(name))
        meta.lore(lore.map { module.parseToComponent(it) })
        item.itemMeta = meta
        return item
    }

    private fun createZoneListItem(zone: CaptureZone): ItemStack {
        val config = module.messagesConfig ?: return ItemStack(Material.STONE)
        val path = "gui.list.zone-item"
        val matStr = config.getString("$path.material", "BEACON") ?: "BEACON"
        val mat = Material.matchMaterial(matStr) ?: Material.BEACON

        val nameTemplate = config.getString("$path.name", "&b&lพื้นที่ยึด: &e%zone%") ?: "&b&lพื้นที่ยึด: &e%zone%"
        val name = nameTemplate.replace("%zone%", zone.name)

        val rawLore = config.getStringList("$path.lore")
        val formattedLore = formatLore(rawLore, zone, "")

        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item
        meta.displayName(module.parseToComponent(name))
        meta.lore(formattedLore.map { module.parseToComponent(it) })
        item.itemMeta = meta
        return item
    }

    private fun createEditorItem(key: String, zone: CaptureZone, value: String): ItemStack {
        val config = module.messagesConfig ?: return ItemStack(Material.STONE)
        val path = "gui.editor.items.$key"
        val matStr = config.getString("$path.material", "STONE") ?: "STONE"
        val mat = Material.matchMaterial(matStr) ?: Material.STONE
        val name = config.getString("$path.name", "") ?: ""
        val rawLore = config.getStringList("$path.lore")

        val formattedName = name.replace("%zone%", zone.name).replace("%status%", getStatusText(zone))
        val formattedLore = formatLore(rawLore, zone, value)

        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item
        meta.displayName(module.parseToComponent(formattedName))
        meta.lore(formattedLore.map { module.parseToComponent(it) })
        item.itemMeta = meta
        return item
    }

    private fun formatLore(lore: List<String>, zone: CaptureZone, value: String): List<String> {
        val formatted = mutableListOf<String>()
        for (line in lore) {
            if (line.contains("%commands%")) {
                if (zone.rewardCommands.isEmpty()) {
                    formatted.add("  &c- ไม่มีรางวัล")
                } else {
                    for (cmd in zone.rewardCommands) {
                        formatted.add("  &a- &f$cmd")
                    }
                }
            } else {
                val newLine = line
                    .replace("%zone%", zone.name)
                    .replace("%world%", zone.worldName)
                    .replace("%minx%", zone.minX.toString())
                    .replace("%maxx%", zone.maxX.toString())
                    .replace("%miny%", zone.minY.toString())
                    .replace("%maxy%", zone.maxY.toString())
                    .replace("%minz%", zone.minZ.toString())
                    .replace("%maxz%", zone.maxZ.toString())
                    .replace("%displayy%", zone.displayY.toString())
                    .replace("%cooldown%", zone.cooldownTimeSeconds.toString())
                    .replace("%value%", value)
                    .replace("%status%", getStatusText(zone))
                formatted.add(newLine)
            }
        }
        return formatted
    }

    private fun getStatusText(zone: CaptureZone): String {
        val config = module.config ?: return zone.status.name
        return when (zone.status) {
            CaptureZone.ZoneStatus.IDLE -> config.getString("placeholder.status.idle", "Idle") ?: "Idle"
            CaptureZone.ZoneStatus.CAPTURING -> {
                val template = config.getString("placeholder.status.capturing", "Capturing") ?: "Capturing"
                val remaining = zone.remainingCaptureSeconds
                val elapsed = zone.captureTimeSeconds - remaining
                template
                    .replace("%remaining%", remaining.toString())
                    .replace("%elapsed%", elapsed.toString())
                    .replace("%total%", zone.captureTimeSeconds.toString())
                    .replace("%time%", remaining.toString())
            }
            CaptureZone.ZoneStatus.GRACE_PERIOD -> {
                val template = config.getString("placeholder.status.grace_period", "Grace Period") ?: "Grace Period"
                val remaining = zone.remainingGraceSeconds
                template
                    .replace("%remaining%", remaining.toString())
                    .replace("%total%", zone.gracePeriodSeconds.toString())
                    .replace("%time%", remaining.toString())
            }
            CaptureZone.ZoneStatus.COOLDOWN -> {
                val template = config.getString("placeholder.status.cooldown", "Cooldown") ?: "Cooldown"
                val remaining = zone.remainingCooldownSeconds
                template
                    .replace("%cooldown%", remaining.toString())
                    .replace("%total%", zone.cooldownTimeSeconds.toString())
                    .replace("%time%", remaining.toString())
            }
        }
    }

    private fun createDecorationItem(material: Material, name: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(module.parseToComponent(name))
        item.itemMeta = meta
        return item
    }

    private fun openCreateDialog(player: Player) {
        val title = "🆕 สร้างพื้นที่ยึดครองใหม่"
        val body = "ระบบพบพิกัดแคชการตั้งมุม pos1 และ pos2 เรียบร้อยแล้ว\nโปรดป้อนชื่อโซนภาษาอังกฤษที่ต้องการสร้างลงในช่องข้อมูลด้านล่าง"
        val inputLabel = "ชื่อพื้นที่ยึดครอง (Zone Name)"

        openSettingDialog(player, null, title, body, inputLabel, "") { input ->
            val name = input.trim().replace(" ", "_")
            if (name.isNotEmpty()) {
                module.plugin.server.globalRegionScheduler.execute(module.plugin) {
                    player.performCommand("ctf create $name")
                }
            }
        }
    }

    private fun openSettingDialog(
        player: Player,
        zone: CaptureZone?,
        titleText: String,
        bodyText: String,
        inputLabelText: String,
        defaultValue: String,
        onSubmit: (String) -> Unit
    ) {
        val title = module.parseToComponent(titleText)
        val bodyComponent = module.parseToComponent(bodyText)
        val inputLabel = module.parseToComponent(inputLabelText)
        val buttonSubmit = module.parseToComponent("&a✔ ยืนยันข้อมูล")

        val textInput = DialogInput.text("val", inputLabel)
            .width(200)
            .maxLength(64)
            .build()

        val submitAction = ActionButton.builder(buttonSubmit)
            .action(DialogAction.customClick(DialogActionCallback { response, audience ->
                val clickedPlayer = audience as? Player ?: return@DialogActionCallback
                val input = response.getText("val") ?: ""
                clickedPlayer.scheduler.execute(module.plugin, {
                    onSubmit(input)
                }, null, 0L)
            }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
            .build()

        val dialog = Dialog.create { builder ->
            builder.empty()
                .type(DialogType.notice(submitAction))
                .base(
                    DialogBase.builder(title)
                        .body(listOf(DialogBody.plainMessage(bodyComponent)))
                        .inputs(listOf(textInput))
                        .canCloseWithEscape(true)
                        .build()
                )
        }

        player.showDialog(dialog)
    }

    private fun openSettingDialog(
        player: Player,
        zone: CaptureZone,
        settingName: String,
        defaultValue: String,
        onSubmit: (String) -> Unit
    ) {
        val title = "⚙ ตั้งค่าโซน: ${zone.name}"
        val body = "กรุณากรอกค่าสำหรับตั้งค่า: &e$settingName\n&7ค่าปัจจุบัน: &a$defaultValue"
        openSettingDialog(player, zone, title, body, settingName, defaultValue, onSubmit)
    }

    private fun getEditorItemKeyBySlot(slot: Int): String? {
        val config = module.messagesConfig ?: return null
        val section = config.getConfigurationSection("gui.editor.items") ?: return null
        for (key in section.getKeys(false)) {
            if (section.getInt("$key.slot", -1) == slot) {
                return key
            }
        }
        return null
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.inventory.holder as? CTFGUIHolder ?: return
        event.isCancelled = true

        val slot = event.rawSlot
        val zoneName = holder.zoneName
        val config = module.messagesConfig

        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.4f, 1.0f)

        if (holder.pageType == PageType.LIST) {
            val createSlot = config?.getInt("gui.list.create-button.slot", 49) ?: 49
            val closeSlot = config?.getInt("gui.list.close-button.slot", 53) ?: 53

            if (slot in 9..44) {
                val zonesList = module.zones.values.toList()
                val idx = slot - 9
                if (idx in zonesList.indices) {
                    val clickedZone = zonesList[idx]
                    openEditorGUI(player, clickedZone.name)
                }
            } else if (slot == createSlot) {
                val p1 = module.selectionPos1[player.uniqueId]
                val p2 = module.selectionPos2[player.uniqueId]
                if (p1 == null || p2 == null) {
                    val msg = config?.getString("admin.select-points-first", "%prefix% &cกรุณาเลือกตำแหน่ง pos1 และ pos2 ให้ครบถ้วนก่อนสร้างโซน!") ?: "%prefix% &cกรุณาเลือกตำแหน่ง pos1 และ pos2 ให้ครบถ้วนก่อนสร้างโซน!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return
                }
                openCreateDialog(player)
            } else if (slot == closeSlot) {
                player.closeInventory()
            }
        } else if (holder.pageType == PageType.EDITOR && zoneName != null) {
            val zone = module.getZone(zoneName) ?: return
            val clickedKey = getEditorItemKeyBySlot(slot) ?: return

            when (clickedKey) {
                "display-y" -> {
                    openSettingDialog(player, zone, "ระดับความสูงการแสดงพาทิเคิล (Y)", zone.displayY.toString()) { input ->
                        val yVal = input.toDoubleOrNull()
                        if (yVal != null) {
                            zone.displayY = yVal
                            zone.cachedOutlinePoints = null
                            module.saveZoneToConfig(zone)
                            val msg = config?.getString("admin.edit-display-y-success", "%prefix% &aแก้ไขความสูงแสดงผลพาทิเคิลของ &e%zone% &aสำเร็จเป็น Y=%y%") ?: "%prefix% &aแก้ไขความสูงแสดงผลพาทิเคิลของ &e%zone% &aสำเร็จเป็น Y=%y%"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zone.name).replace("%y%", yVal.toString()))))
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "min-y" -> {
                    openSettingDialog(player, zone, "ความสูง Y ต่ำสุดสำหรับตรวจจับ", zone.minY.toString()) { input ->
                        val yVal = input.toIntOrNull()
                        if (yVal != null) {
                            zone.minY = yVal
                            module.saveZoneToConfig(zone)
                            val msg = config?.getString("admin.edit-min-y-success", "%prefix% &aแก้ไขขอบเขตความสูงตรวจจับต่ำสุดของ &e%zone% &aสำเร็จเป็น Y=%y%") ?: "%prefix% &aแก้ไขขอบเขตความสูงตรวจจับต่ำสุดของ &e%zone% &aสำเร็จเป็น Y=%y%"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zone.name).replace("%y%", yVal.toString()))))
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "max-y" -> {
                    openSettingDialog(player, zone, "ความสูง Y สูงสุดสำหรับตรวจจับ", zone.maxY.toString()) { input ->
                        val yVal = input.toIntOrNull()
                        if (yVal != null) {
                            zone.maxY = yVal
                            module.saveZoneToConfig(zone)
                            val msg = config?.getString("admin.edit-max-y-success", "%prefix% &aแก้ไขขอบเขตความสูงตรวจจับสูงสุดของ &e%zone% &aสำเร็จเป็น Y=%y%") ?: "%prefix% &aแก้ไขขอบเขตความสูงตรวจจับสูงสุดของ &e%zone% &aสำเร็จเป็น Y=%y%"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zone.name).replace("%y%", yVal.toString()))))
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "capture-time" -> {
                    openSettingDialog(player, zone, "เวลาในการยึดครอง (วินาที)", zone.captureTimeSeconds.toString()) { input ->
                        val sec = input.toIntOrNull()
                        if (sec != null && sec > 0) {
                            zone.captureTimeSeconds = sec
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตเวลายึดครองของ &e${zone.name} &aเป็น &e$sec &aวินาที")))
                            openEditorGUI(player, zone.name)
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                    }
                }
                "cooldown-time" -> {
                    openSettingDialog(player, zone, "เวลาคูลดาวน์ (วินาที)", zone.cooldownTimeSeconds.toString()) { input ->
                        val sec = input.toIntOrNull()
                        if (sec != null && sec >= 0) {
                            zone.cooldownTimeSeconds = sec
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตเวลาคูลดาวน์ของ &e${zone.name} &aเป็น &e$sec &aวินาที")))
                            openEditorGUI(player, zone.name)
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                    }
                }
                "grace-period" -> {
                    openSettingDialog(player, zone, "ช่วงเวลาผ่อนผัน (วินาที)", zone.gracePeriodSeconds.toString()) { input ->
                        val sec = input.toIntOrNull()
                        if (sec != null && sec >= 0) {
                            zone.gracePeriodSeconds = sec
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตเวลาผ่อนผันของ &e${zone.name} &aเป็น &e$sec &aวินาที")))
                            openEditorGUI(player, zone.name)
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                    }
                }
                "rewards" -> {
                    if (event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT) {
                        zone.rewardCommands = emptyList()
                        module.saveZoneToConfig(zone)
                        player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aล้างคำสั่งรางวัลของโซน &e${zone.name} &aทั้งหมดแล้ว")))
                        openEditorGUI(player, zone.name)
                    } else {
                        openSettingDialog(player, zone, "เพิ่มคำสั่งรางวัล (Console Command)", "give %player% diamond 5") { input ->
                            if (input.trim().isNotEmpty()) {
                                val list = zone.rewardCommands.toMutableList()
                                list.add(input.trim())
                                zone.rewardCommands = list
                                module.saveZoneToConfig(zone)
                                player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aเพิ่มคำสั่งรางวัลสำเร็จสำหรับ &e${zone.name}: &e$input")))
                                openEditorGUI(player, zone.name)
                            }
                        }
                    }
                }
                "toggle-particles" -> {
                    zone.particleEnabled = !zone.particleEnabled
                    module.saveZoneToConfig(zone)
                    openEditorGUI(player, zone.name)
                }
                "particle-type" -> {
                    openSettingDialog(player, zone, "ชนิดพาทิเคิล", zone.particleType) { input ->
                        val type = input.uppercase().trim()
                        try {
                            Particle.valueOf(type)
                            zone.particleType = type
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aเปลี่ยนชนิดพาทิเคิลเป็น &e$type")))
                        } catch (e: Exception) {
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &cไม่พบชนิดพาทิเคิลชื่อ &e$type")))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "particle-density" -> {
                    openSettingDialog(player, zone, "ความหนาแน่นพาทิเคิล (บล็อก)", zone.particleDensity.toString()) { input ->
                        val density = input.toDoubleOrNull()
                        if (density != null && density > 0.05) {
                            zone.particleDensity = density
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตระยะห่างเป็น &e$density &aบล็อก")))
                            openEditorGUI(player, zone.name)
                        } else {
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &cกรุณากรอกตัวเลขมากกว่า 0.05")))
                        }
                    }
                }
                "particle-size" -> {
                    openSettingDialog(player, zone, "ขนาดพาทิเคิล DUST", zone.particleSize.toString()) { input ->
                        val size = input.toFloatOrNull()
                        if (size != null && size > 0.0f) {
                            zone.particleSize = size
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตขนาดพาทิเคิลเป็น &e$size")))
                            openEditorGUI(player, zone.name)
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                    }
                }
                "particle-color" -> {
                    openSettingDialog(player, zone, "สีพาทิเคิล HEX (เช่น #00FF80 หรือ 00FF80)", zone.particleColorHex) { input ->
                        var hex = input.trim()
                        if (hex.isNotEmpty()) {
                            if (!hex.startsWith("#")) {
                                hex = "#$hex"
                            }
                            if (hex.length == 7) {
                                zone.particleColorHex = hex
                                module.saveZoneToConfig(zone)
                                player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตสีพาทิเคิลเป็น &e$hex")))
                            } else {
                                player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &cรูปแบบสี HEX ไม่ถูกต้อง! ตัวอย่าง: #00FF80")))
                            }
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "hide-cooldown" -> {
                    zone.hideDuringCooldown = !zone.hideDuringCooldown
                    module.saveZoneToConfig(zone)
                    openEditorGUI(player, zone.name)
                }
                "cooldown-type" -> {
                    openSettingDialog(player, zone, "ชนิดพาทิเคิลคูลดาวน์", zone.cooldownParticleType) { input ->
                        val type = input.uppercase().trim()
                        try {
                            Particle.valueOf(type)
                            zone.cooldownParticleType = type
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aเปลี่ยนชนิดพาทิเคิลคูลดาวน์เป็น &e$type")))
                        } catch (e: Exception) {
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &cไม่พบชนิดพาทิเคิลคูลดาวน์ชื่อ &e$type")))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "cooldown-size" -> {
                    openSettingDialog(player, zone, "ขนาดพาทิเคิลคูลดาวน์ DUST", zone.cooldownParticleSize.toString()) { input ->
                        val size = input.toFloatOrNull()
                        if (size != null && size > 0.0f) {
                            zone.cooldownParticleSize = size
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตขนาดพาทิเคิลคูลดาวน์เป็น &e$size")))
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "cooldown-color" -> {
                    openSettingDialog(player, zone, "สีพาทิเคิลคูลดาวน์ HEX (เช่น #FF0000 หรือ FF0000)", zone.cooldownParticleColorHex) { input ->
                        var hex = input.trim()
                        if (hex.isNotEmpty()) {
                            if (!hex.startsWith("#")) {
                                hex = "#$hex"
                            }
                            if (hex.length == 7) {
                                zone.cooldownParticleColorHex = hex
                                module.saveZoneToConfig(zone)
                                player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตสีพาทิเคิลคูลดาวน์เป็น &e$hex")))
                            } else {
                                player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &cรูปแบบสี HEX ไม่ถูกต้อง! ตัวอย่าง: #FF0000")))
                            }
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "height-offset" -> {
                    openSettingDialog(player, zone, "ความสูงของพาทิเคิลเหนือพื้น (บล็อก)", zone.particleHeightOffset.toString()) { input ->
                        val offset = input.toDoubleOrNull()
                        if (offset != null) {
                            zone.particleHeightOffset = offset
                            zone.cachedOutlinePoints = null
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตระดับความสูงลอยตัวเป็น &e$offset &aบล็อก")))
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "spawn-interval" -> {
                    openSettingDialog(player, zone, "ความถี่การเกิดพาทิเคิล (วินาที)", zone.particleSpawnInterval.toString()) { input ->
                        val num = input.toDoubleOrNull()
                        if (num != null && num >= 0.0) {
                            zone.particleSpawnInterval = num
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตความถี่การเกิดพาทิเคิลเป็น &e$num &aวินาที")))
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "duration" -> {
                    openSettingDialog(player, zone, "ระยะเวลาแสดงผลพาทิเคิล (วินาที)", zone.particleDuration.toString()) { input ->
                        val num = input.toDoubleOrNull()
                        if (num != null && num >= 0.0) {
                            zone.particleDuration = num
                            module.saveZoneToConfig(zone)
                            player.sendMessage(module.parseToComponent(module.formatMessage("%prefix% &aอัปเดตระยะเวลาแสดงผลพาทิเคิลเป็น &e$num &aวินาที")))
                        } else {
                            val msg = config?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                        }
                        openEditorGUI(player, zone.name)
                    }
                }
                "force-win" -> {
                    zone.remainingCaptureSeconds = 0
                    zone.status = CaptureZone.ZoneStatus.CAPTURING
                    zone.occupantUuid = player.uniqueId
                    zone.occupantName = player.name
                    val msg = config?.getString("admin.forcewin-success", "%prefix% &aบังคับให้ผู้เล่นยึดพื้นที่ &e%zone% &aสำเร็จเรียบร้อย!") ?: "%prefix% &aบังคับให้ผู้เล่นยึดพื้นที่ &e%zone% &aสำเร็จเรียบร้อย!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zone.name))))
                    openEditorGUI(player, zone.name)
                }
                "force-cooldown" -> {
                    zone.status = CaptureZone.ZoneStatus.COOLDOWN
                    zone.remainingCooldownSeconds = zone.cooldownTimeSeconds
                    zone.occupantUuid = null
                    val msg = config?.getString("admin.forcecooldown-success", "%prefix% &aบังคับให้พื้นที่ &e%zone% &aเข้าสู่สถานะคูลดาวน์แล้ว!") ?: "%prefix% &aบังคับให้พื้นที่ &e%zone% &aเข้าสู่สถานะคูลดาวน์แล้ว!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zone.name))))
                    openEditorGUI(player, zone.name)
                }
                "delete-zone" -> {
                    val originalName = zone.name
                    module.zones.remove(originalName)
                    module.removeZoneFromConfig(originalName)
                    val msg = config?.getString("admin.delete-success", "%prefix% &aลบพื้นที่ยึดครอง &e%zone% &aออกจากระบบและไฟล์ข้อมูลแล้ว") ?: "%prefix% &aลบพื้นที่ยึดครอง &e%zone% &aออกจากระบบและไฟล์ข้อมูลแล้ว"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", originalName))))
                    openMainGUI(player)
                }
                "back-button" -> {
                    openMainGUI(player)
                }
            }
        }
    }
}
