package core.luminaworld.modules.features.Shop

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.inventory.meta.Damageable

class ShopManager(private val plugin: LuminaCore) {
    companion object {
        lateinit var instance: ShopManager
            private set
    }

    val shopFolder = File(plugin.dataFolder, "features/Shop")
    val sectionsFolder = File(shopFolder, "sections")
    val shopsFolder = File(shopFolder, "shops")
    val langFolder = File(shopFolder, "LanguageFiles")

    val sections = ConcurrentHashMap<String, ShopSection>()
    val shops = ConcurrentHashMap<String, Map<Int, ShopPage>>()
    
    var shopConfig: YamlConfiguration? = null
    var langConfig: YamlConfiguration? = null

    init {
        instance = this
        setupFolders()
        loadConfigs()
    }

    fun setupFolders() {
        if (!shopFolder.exists()) shopFolder.mkdirs()
        if (!sectionsFolder.exists()) sectionsFolder.mkdirs()
        if (!shopsFolder.exists()) shopsFolder.mkdirs()
        if (!langFolder.exists()) langFolder.mkdirs()

        // คัดลอก default files จาก resource หากไม่มีไฟล์ในเครื่อง
        copyDefaultResource("Shop.yml", File(shopFolder, "Shop.yml"))
        copyDefaultResource("sections/ExampleSection.yml", File(sectionsFolder, "ExampleSection.yml"))
        copyDefaultResource("shops/ExampleShop.yml", File(shopsFolder, "ExampleShop.yml"))

        // ลิสต์ไฟล์ภาษาที่จะคัดลอก (เหลือเฉพาะ TH และ EN)
        val langFiles = listOf("lang-th.yml", "lang-en.yml")
        for (lFile in langFiles) {
            copyDefaultResource("LanguageFiles/$lFile", File(langFolder, lFile))
        }

        // ลิสต์ไฟล์ที่จะคัดลอก
        val defaultFiles = listOf(
            "Blocks.yml", "Commands.yml", "Decoration.yml", "Dyes.yml", 
            "Enchanting.yml", "Farming.yml", "Food.yml", "Miscellaneous.yml", 
            "Mobs.yml", "Music.yml", "NBTItems.yml", "Ores.yml", "Potions.yml", 
            "Redstone.yml", "SpawnEggs.yml", "Spawners.yml", "Workstations.yml", 
            "Z_EverythingElse.yml"
        )

        for (fileName in defaultFiles) {
            copyDefaultResource("sections/$fileName", File(sectionsFolder, fileName))
            copyDefaultResource("shops/$fileName", File(shopsFolder, fileName))
        }
    }

    private fun copyDefaultResource(resourcePath: String, targetFile: File) {
        if (targetFile.exists()) return
        try {
            val resourceStream = javaClass.classLoader.getResourceAsStream("core/luminaworld/modules/features/Shop/$resourcePath")
            if (resourceStream != null) {
                Files.copy(resourceStream, targetFile.toPath())
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to copy default shop resource $resourcePath: ${e.message}")
        }
    }

    fun loadConfigs() {
        sections.clear()
        shops.clear()

        // โหลด Shop.yml
        val configFile = File(shopFolder, "Shop.yml")
        if (configFile.exists()) {
            shopConfig = YamlConfiguration.loadConfiguration(configFile)
        }

        // ดึงค่า Global Dynamic Pricing Fallbacks
        val globalDpEnabled = shopConfig?.getBoolean("dynamic-pricing.enabled", false) ?: false
        val globalDpMaxStock = shopConfig?.getInt("dynamic-pricing.global-max-stock", 10000) ?: 10000
        val globalDpBuyScaling = shopConfig?.getDouble("dynamic-pricing.global-buy-price-scaling", 150.0) ?: 150.0
        val globalDpSellScaling = shopConfig?.getDouble("dynamic-pricing.global-sell-price-scaling", 50.0) ?: 50.0

        // โหลดไฟล์ภาษามาตรฐาน (รองรับ lang-th, lang-en ฯลฯ)
        val langName = shopConfig?.getString("settings.language", "lang-th") ?: "lang-th"
        val targetLangName = if (langName.startsWith("lang-")) "$langName.yml" else "lang-$langName.yml"
        var langFile = File(langFolder, targetLangName)
        if (!langFile.exists()) {
            langFile = File(langFolder, "$langName.yml")
        }
        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile)
        } else {
            val fallback = File(langFolder, "th_TH.yml")
            if (fallback.exists()) langConfig = YamlConfiguration.loadConfiguration(fallback)
        }

        // โหลด sections
        val sectionFiles = sectionsFolder.listFiles { _, name -> name.endsWith(".yml") } ?: emptyArray()
        for (file in sectionFiles) {
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val id = file.nameWithoutExtension
                val enabled = config.getBoolean("enable", true)
                if (!enabled) continue

                val rawSlot = config.getInt("slot", -1)
                val slot = if (rawSlot > 0) rawSlot - 1 else rawSlot

                val title = config.getString("title", id) ?: id
                val hidden = config.getBoolean("hidden", false)
                val subSection = config.getBoolean("sub-section", false)
                val displayItem = config.getBoolean("display-item", false)
                val economy = config.getString("economy")
                val closeMenu = config.getBoolean("close-menu", false)
                val clickCommands = config.getStringList("click-commands")
                val fillItemMaterial = config.getString("fill-item.material")
                val fillItemName = config.getString("fill-item.name")
                val fillItemLore = config.getStringList("fill-item.lore")
                val itemLayout = config.getStringList("item-layout")
                val navBarMode = config.getString("nav-bar.mode")

                val itemMat = config.getString("item.material", "STONE") ?: "STONE"
                val itemName = config.getString("item.name")
                val itemLore = config.getStringList("item.lore")
                val itemEnchantments = config.getStringList("item.enchantments")
                val itemGlow = config.getBoolean("item.enchantment-glint", false)
                val itemSkullOwner = config.getString("item.skullowner")
                val itemSkullTexture = config.getString("item.skull-texture")
                val itemArmorColor = config.getString("item.armorcolor")
                val itemStackSize = config.getInt("item.stack-size", 1)
                val itemNbt = config.getString("item.NBTData")

                val dpEnabled = if (config.contains("dynamic-pricing.enabled")) config.getBoolean("dynamic-pricing.enabled") else globalDpEnabled
                val dpMaxStock = config.getInt("dynamic-pricing.max-stock", globalDpMaxStock)
                val dpBuyPriceScaling = config.getDouble("dynamic-pricing.buyPriceScaling", globalDpBuyScaling)
                val dpSellPriceScaling = config.getDouble("dynamic-pricing.sellPriceScaling", globalDpSellScaling)

                val section = ShopSection(
                    id = id,
                    enabled = enabled,
                    slot = slot,
                    title = title,
                    hidden = hidden,
                    subSection = subSection,
                    displayItem = displayItem,
                    economy = economy,
                    closeMenu = closeMenu,
                    clickCommands = clickCommands,
                    fillItemMaterial = fillItemMaterial,
                    fillItemName = fillItemName,
                    fillItemLore = fillItemLore,
                    itemLayout = itemLayout,
                    clickMappings = null,
                    navBarMode = navBarMode,
                    itemMaterial = itemMat,
                    itemName = itemName,
                    itemLore = itemLore,
                    itemEnchantments = itemEnchantments,
                    itemGlow = itemGlow,
                    itemSkullOwner = itemSkullOwner,
                    itemSkullTexture = itemSkullTexture,
                    itemArmorColor = itemArmorColor,
                    itemStackSize = itemStackSize,
                    itemNbt = itemNbt,
                    dpEnabled = dpEnabled,
                    dpMaxStock = dpMaxStock,
                    dpBuyPriceScaling = dpBuyPriceScaling,
                    dpSellPriceScaling = dpSellPriceScaling
                )
                sections[id.lowercase()] = section
            } catch (e: Exception) {
                plugin.logger.severe("Failed to load shop section ${file.name}: ${e.message}")
            }
        }

        // โหลด shops
        val shopFiles = shopsFolder.listFiles { _, name -> name.endsWith(".yml") } ?: emptyArray()
        for (file in shopFiles) {
            try {
                val sectionId = file.nameWithoutExtension.lowercase()
                if (!sections.containsKey(sectionId)) continue

                val config = YamlConfiguration.loadConfiguration(file)
                val pagesSec = config.getConfigurationSection("pages") ?: continue
                val pagesMap = HashMap<Int, ShopPage>()

                for (pageKey in pagesSec.getKeys(false)) {
                    val pageNum = pageKey.replace("page", "").toIntOrNull() ?: continue
                    val guiRows = pagesSec.getInt("$pageKey.gui-rows", 6)
                    val title = pagesSec.getString("$pageKey.title")
                    val itemsSec = pagesSec.getConfigurationSection("$pageKey.items")
                    val page = ShopPage(pageNum, guiRows, title)

                    if (itemsSec != null) {
                        for (itemKey in itemsSec.getKeys(false)) {
                            val rawSlot = itemKey.toIntOrNull() ?: continue
                            val slot = rawSlot - 1
                            val mat = itemsSec.getString("$itemKey.material", "AIR") ?: "AIR"
                            val name = itemsSec.getString("$itemKey.name") ?: itemsSec.getString("$itemKey.displayname")
                            val lore = itemsSec.getStringList("$itemKey.lore")
                            val enchantments = itemsSec.getStringList("$itemKey.enchantments")
                            val buyPrice = if (itemsSec.contains("$itemKey.buy")) itemsSec.getDouble("$itemKey.buy") else null
                            val sellPrice = if (itemsSec.contains("$itemKey.sell")) itemsSec.getDouble("$itemKey.sell") else null
                            val permission = itemsSec.getString("$itemKey.permission")
                            val potionTypes = itemsSec.getStringList("$itemKey.potiontypes")
                            val recipes = itemsSec.getStringList("$itemKey.recipes")
                            val runCmds = itemsSec.getStringList("$itemKey.commands")
                            val nbt = itemsSec.getString("$itemKey.NBTData")
                            val cmdData = if (itemsSec.contains("$itemKey.CustomModelData")) itemsSec.getInt("$itemKey.CustomModelData") else null
                            val stackSize = itemsSec.getInt("$itemKey.quantity", itemsSec.getInt("$itemKey.stack-size", itemsSec.getInt("$itemKey.amount", 1)))

                            val item = ShopItem(
                                id = "$sectionId.$itemKey",
                                sectionId = sectionId,
                                material = mat,
                                name = name,
                                lore = lore,
                                enchantments = enchantments,
                                buyPrice = buyPrice,
                                sellPrice = sellPrice,
                                permission = permission,
                                potionTypes = potionTypes,
                                recipes = recipes,
                                runCommands = runCmds,
                                nbtData = nbt,
                                customModelData = cmdData,
                                itemStackSize = stackSize
                            )
                            page.items[slot] = item
                        }
                    }
                    pagesMap[pageNum] = page
                }
                shops[sectionId] = pagesMap
            } catch (e: Exception) {
                plugin.logger.severe("Failed to load shop items ${file.name}: ${e.message}")
            }
        }
    }

    fun getMsg(path: String, def: String = ""): String {
        var raw = langConfig?.getString(path) ?: langConfig?.getString("messages.$path") ?: def
        val prefix = shopConfig?.getString("settings.prefix", "[LuminaShop]") ?: "[LuminaShop]"
        raw = raw.replace("%prefix%", prefix)
        return core.luminaworld.utils.ColorParser.parse(raw).let { 
            // แปลง Component กลับเป็น Legacy String
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(it)
        }
    }

    /**
     * แปลงวัตถุ ShopItem เป็น Spigot ItemStack
     */
    fun createItemStack(item: ShopItem): ItemStack {
        val mat = Material.matchMaterial(item.material.uppercase()) ?: Material.STONE
        val amt = Math.max(1, Math.min(item.itemStackSize, 64))
        val itemStack = ItemStack(mat, amt)
        val meta = itemStack.itemMeta ?: return itemStack

        // ตั้งชื่อ
        if (!item.name.isNullOrBlank()) {
            meta.displayName(core.luminaworld.utils.ColorParser.parse(item.name))
        }

        // ตั้ง Lore
        if (item.lore != null && item.lore.isNotEmpty()) {
            meta.lore(item.lore.map { core.luminaworld.utils.ColorParser.parse(it) })
        }

        // ตั้ง CustomModelData
        if (item.customModelData != null) {
            meta.setCustomModelData(item.customModelData)
        }

        // ตั้ง Enchantments
        if (item.enchantments != null) {
            for (enchStr in item.enchantments) {
                val parts = enchStr.split(":")
                val keyStr = parts[0].trim().lowercase()
                val lvl = if (parts.size > 1) parts[1].toIntOrNull() ?: 1 else 1
                
                val enchantment = Enchantment.getByKey(NamespacedKey.minecraft(keyStr))
                    ?: Enchantment.getByName(keyStr.uppercase())
                if (enchantment != null) {
                    meta.addEnchant(enchantment, lvl, true)
                }
            }
        }

        // ตั้งค่า Potion
        if (meta is org.bukkit.inventory.meta.PotionMeta && item.potionTypes != null) {
            for (potTypeStr in item.potionTypes) {
                val searchName = potTypeStr.uppercase()
                    .replace("LONG_", "")
                    .replace("STRONG_", "")
                
                val type = org.bukkit.potion.PotionType.values().find { 
                    it.name == searchName || it.name == potTypeStr.uppercase() 
                }
                if (type != null) {
                    meta.basePotionType = type
                }
            }
        }

        itemStack.itemMeta = meta
        return itemStack
    }

    /**
     * แปลงปุ่มของ ShopSection เป็น Spigot ItemStack
     */
    fun createSectionItemStack(section: ShopSection): ItemStack {
        val mat = Material.matchMaterial(section.itemMaterial.uppercase()) ?: Material.BOOK
        val itemStack = ItemStack(mat)
        val meta = itemStack.itemMeta ?: return itemStack

        if (!section.itemName.isNullOrBlank()) {
            meta.displayName(core.luminaworld.utils.ColorParser.parse(section.itemName))
        }

        if (section.itemLore != null && section.itemLore.isNotEmpty()) {
            meta.lore(section.itemLore.map { core.luminaworld.utils.ColorParser.parse(it) })
        }

        if (section.itemGlow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
        }

        // ตั้งสกินหัว (Skull texture)
        if (meta is SkullMeta) {
            if (!section.itemSkullOwner.isNullOrBlank()) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(section.itemSkullOwner))
            } else if (!section.itemSkullTexture.isNullOrBlank()) {
                try {
                    val profileClass = Class.forName("com.mojang.authlib.GameProfile")
                    val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
                    val profileConstructor = profileClass.getConstructor(UUID::class.java, String::class.java)
                    val profile = profileConstructor.newInstance(UUID.randomUUID(), null)
                    
                    val propertyConstructor = propertyClass.getConstructor(String::class.java, String::class.java)
                    val property = propertyConstructor.newInstance("textures", section.itemSkullTexture)
                    
                    val getPropertiesMethod = profileClass.getMethod("getProperties")
                    val propertiesMap = getPropertiesMethod.invoke(profile)
                    val putMethod = propertiesMap.javaClass.getMethod("put", Any::class.java, Any::class.java)
                    putMethod.invoke(propertiesMap, "textures", property)

                    val profileField = meta.javaClass.getDeclaredField("profile")
                    profileField.isAccessible = true
                    profileField.set(meta, profile)
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to set custom skull texture: ${e.message}")
                }
            }
        }

        itemStack.itemMeta = meta
        return itemStack
    }

    /**
     * ตรวจสอบสิทธิ์การใช้งานทั่วไปของผู้เล่นตามการตั้งค่าใน Shop.yml
     */
    fun checkPermission(player: org.bukkit.entity.Player, nodeKey: String, specificPerm: String? = null): Boolean {
        val globalEnabled = shopConfig?.getBoolean("permissions.enabled", true) ?: true
        if (!globalEnabled) return true
        if (player.isOp) return true

        val check = shopConfig?.getBoolean("permissions.$nodeKey.check", true) ?: true
        if (!check) return true

        val perm = specificPerm ?: shopConfig?.getString("permissions.$nodeKey.permission") ?: return true
        return player.hasPermission(perm)
    }

    /**
     * ตรวจสอบสิทธิ์การเข้าถึงหมวดหมู่ร้านค้า (รองรับ wildcard luminashop.section.*)
     */
    fun checkSectionPermission(player: org.bukkit.entity.Player, sectionId: String): Boolean {
        val globalEnabled = shopConfig?.getBoolean("permissions.enabled", true) ?: true
        if (!globalEnabled) return true
        if (player.isOp) return true

        val check = shopConfig?.getBoolean("permissions.section-access.check", true) ?: true
        if (!check) return true

        val wildcardPerm = shopConfig?.getString("permissions.section-access.wildcard-permission", "luminashop.section.*") ?: "luminashop.section.*"
        if (player.hasPermission(wildcardPerm)) return true

        val prefix = shopConfig?.getString("permissions.section-access.permission-prefix", "luminashop.section.") ?: "luminashop.section."
        return player.hasPermission("$prefix$sectionId")
    }

    /**
     * ส่งการแจ้งเตือน Webhook เข้า Discord รายประเภท (bought / sold)
     */
    fun sendDiscordShopWebhook(player: org.bukkit.entity.Player, type: String, itemName: String, amount: Int, formattedPrice: String) {
        val enabled = shopConfig?.getBoolean("discord-webhooks.enabled", false) ?: false
        if (!enabled) return

        val webhookUrl = shopConfig?.getString("discord-webhooks.webhook-url", "") ?: ""
        if (webhookUrl.isBlank() || webhookUrl == "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL") return

        val notifyKey = if (type.equals("bought", ignoreCase = true)) "notify-bought" else "notify-sold"
        val notifyEnabled = shopConfig?.getBoolean("discord-webhooks.$notifyKey", true) ?: true
        if (!notifyEnabled) return

        val sectionKey = if (type.equals("bought", ignoreCase = true)) "bought" else "sold"
        val section = shopConfig?.getConfigurationSection("discord-webhooks.$sectionKey") ?: return

        val nowMs = System.currentTimeMillis()
        val instant = java.time.Instant.ofEpochMilli(nowMs)
        val fullFormatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(java.time.ZoneId.systemDefault())
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault())

        val replacer: (String) -> String = { input ->
            input.replace("%player%", player.name)
                .replace("%item%", itemName)
                .replace("%amount%", amount.toString())
                .replace("%price%", formattedPrice)
                .replace("%date%", dateFormatter.format(instant))
                .replace("%time%", timeFormatter.format(instant))
                .replace("%datetime%", fullFormatter.format(instant))
        }

        val webhook = core.luminaworld.util.DiscordWebhook.fromConfig(webhookUrl, section, replacer)
        val username = shopConfig?.getString("discord-webhooks.username", "LuminaShop Audit") ?: "LuminaShop Audit"
        val avatar = shopConfig?.getString("discord-webhooks.avatar-url", "") ?: ""

        webhook.setUsername(username)
        if (avatar.isNotBlank()) {
            webhook.setAvatarUrl(avatar)
        }

        webhook.sendAsync(plugin)
    }
}

