package core.luminaworld.modules.features.Shop

import core.luminaworld.LuminaCore
import core.luminaworld.gui.LuminaGUI
import core.luminaworld.gui.LuminaGUIHolder
import core.luminaworld.modules.system.EconomyBridge.EconomyBridgeService
import core.luminaworld.modules.system.DynamicPricing.DynamicPricingService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ShopGUIManager(private val plugin: LuminaCore) {
    companion object {
        lateinit var instance: ShopGUIManager
            private set
    }

    init {
        instance = this
    }

    /**
     * 1. เปิดเมนูหลักแสดงหมวดหมู่ร้านค้า (/shop)
     */
    fun openMainMenu(player: Player) {
        val manager = ShopManager.instance
        val defaultTitle = manager.getMsg("inventory-main-shop-title", "&8ร้านค้าเซิร์ฟเวอร์")
        val titleStr = manager.shopConfig?.getString("settings.menu-titles.main-menu", defaultTitle) ?: defaultTitle
        val size = manager.shopConfig?.getInt("settings.main-menu-size", 54) ?: 54
        
        val gui = LuminaGUI(plugin, core.luminaworld.utils.ColorParser.parse(titleStr), size)
        
        // วาดขอบและไอเทมตกแต่ง (ถ้ามี)
        val fillMatStr = manager.shopConfig?.getString("settings.main-menu-fill-item.material")
        if (!fillMatStr.isNullOrBlank()) {
            val fillMat = Material.matchMaterial(fillMatStr.uppercase())
            if (fillMat != null) {
                val fillItem = ItemStack(fillMat)
                val fillMeta = fillItem.itemMeta
                if (fillMeta != null) {
                    fillMeta.displayName(core.luminaworld.utils.ColorParser.parse(" "))
                    fillItem.itemMeta = fillMeta
                }
                gui.fill(fillItem)
            }
        }

        // วาดปุ่มหมวดหมู่ทั้งหมด
        for (section in manager.sections.values) {
            if (!section.enabled || section.hidden || section.subSection) continue
            if (section.slot < 0 || section.slot >= size) continue

            val sectionItem = manager.createSectionItemStack(section)
            gui.setButton(section.slot, sectionItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                
                if (!manager.checkSectionPermission(p, section.id)) {
                    p.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการเข้าถึงหมวดหมู่นี้"))
                    return@setButton
                }

                if (section.displayItem) return@setButton
                
                if (section.clickCommands.isNotEmpty()) {
                    for (cmd in section.clickCommands) {
                        val formattedCmd = cmd.replace("%player%", p.name)
                        if (formattedCmd.startsWith("/")) {
                            p.performCommand(formattedCmd.substring(1))
                        } else {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formattedCmd)
                        }
                    }
                    if (section.closeMenu) {
                        p.closeInventory()
                    }
                    return@setButton
                }

                openCategoryMenu(p, section.id, 1)
            }
        }

        gui.open(player)
    }

    /**
     * 2. เปิดหน้าร้านค้าย่อยเฉพาะหมวดหมู่
     */
    fun openCategoryMenu(player: Player, sectionId: String, pageNum: Int) {
        val manager = ShopManager.instance
        val section = manager.sections[sectionId.lowercase()] ?: return
        val shopPages = manager.shops[sectionId.lowercase()] ?: return
        val page = shopPages[pageNum] ?: return

        val titleStr = page.title ?: section.title
        val size = page.guiRows * 9
        
        val gui = LuminaGUI(plugin, core.luminaworld.utils.ColorParser.parse(titleStr), size)

        // เติมไอเทมพื้นหลัง (fill-item)
        if (!section.fillItemMaterial.isNullOrBlank()) {
            val fillMat = Material.matchMaterial(section.fillItemMaterial.uppercase())
            if (fillMat != null) {
                val fillItem = ItemStack(fillMat)
                val fillMeta = fillItem.itemMeta
                if (fillMeta != null) {
                    fillMeta.displayName(core.luminaworld.utils.ColorParser.parse(section.fillItemName ?: " "))
                    if (section.fillItemLore != null) {
                        fillMeta.lore(section.fillItemLore.map { core.luminaworld.utils.ColorParser.parse(it) })
                    }
                    fillItem.itemMeta = fillMeta
                }
                gui.fill(fillItem)
            }
        }

        // วาดสินค้า
        for ((slot, item) in page.items) {
            if (slot < 0 || slot >= size) continue

            // เช็คราคาผันผวนรายชิ้นเดี่ยว (สำหรับแสดงในหน้าร้าน)
            val finalBuy = getBuyPrice(item, section)
            val finalSell = getSellPrice(item, section)

            val displayItem = manager.createItemStack(item)
            val meta = displayItem.itemMeta
            if (meta != null) {
                val lore = meta.lore()?.toMutableList() ?: ArrayList()
                
                val ecoType = section.economy ?: "VAULT"
                val formatter = EconomyBridgeService.instance.parseEconomy(ecoType)
                
                val baseBuy = item.buyPrice
                val baseSell = item.sellPrice

                if (finalBuy != null && finalBuy > 0.0) {
                    if (baseBuy != null && baseBuy > 0.0 && section.dpEnabled && Math.abs(finalBuy - baseBuy) > 0.001) {
                        val diffPct = Math.abs((finalBuy - baseBuy) / baseBuy * 100.0)
                        val formattedPct = String.format(Locale.ENGLISH, "%.1f", diffPct)
                        val msgKey = if (finalBuy > baseBuy) "buy-price-increase" else "buy-price-decrease"
                        val defMsg = if (finalBuy > baseBuy) "&7ราคาซื้อ: &a%price% &8(&c▲%percent%%&8)" else "&7ราคาซื้อ: &a%price% &8(&a▼%percent%%&8)"
                        lore.add(core.luminaworld.utils.ColorParser.parse(
                            manager.getMsg(msgKey, defMsg)
                                .replace("%price%", formatter.format(finalBuy))
                                .replace("%percent%", formattedPct)
                        ))
                    } else {
                        lore.add(core.luminaworld.utils.ColorParser.parse(
                            manager.getMsg("buy-price", "&7ราคาซื้อ: &a%price%")
                                .replace("%price%", formatter.format(finalBuy))
                        ))
                    }
                } else {
                    lore.add(core.luminaworld.utils.ColorParser.parse(manager.getMsg("cannot-buy-this-item-lore", "&7ราคาซื้อ: &cซื้อไม่ได้")))
                }
                
                if (finalSell != null && finalSell > 0.0) {
                    if (baseSell != null && baseSell > 0.0 && section.dpEnabled && Math.abs(finalSell - baseSell) > 0.001) {
                        val diffPct = Math.abs((finalSell - baseSell) / baseSell * 100.0)
                        val formattedPct = String.format(Locale.ENGLISH, "%.1f", diffPct)
                        val msgKey = if (finalSell > baseSell) "sell-price-increase" else "sell-price-decrease"
                        val defMsg = if (finalSell > baseSell) "&7ราคาขาย: &e%price% &8(&a▲%percent%%&8)" else "&7ราคาขาย: &e%price% &8(&c▼%percent%%&8)"
                        lore.add(core.luminaworld.utils.ColorParser.parse(
                            manager.getMsg(msgKey, defMsg)
                                .replace("%price%", formatter.format(finalSell))
                                .replace("%percent%", formattedPct)
                        ))
                    } else {
                        lore.add(core.luminaworld.utils.ColorParser.parse(
                            manager.getMsg("sell-price", "&7ราคาขาย: &e%price%")
                                .replace("%price%", formatter.format(finalSell))
                        ))
                    }
                } else {
                    lore.add(core.luminaworld.utils.ColorParser.parse(manager.getMsg("cannot-sell-this-item-lore", "&7ราคาขาย: &cขายไม่ได้")))
                }

                meta.lore(lore)
                displayItem.itemMeta = meta
            }

            gui.setButton(slot, displayItem) { p, click ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                
                val isBuy = click != ClickType.RIGHT && click != ClickType.SHIFT_RIGHT
                val actionNode = if (isBuy) "item-buy" else "item-sell"
                val hasItemSpecificPerm = item.permission.isNullOrBlank() || p.hasPermission(item.permission) || p.isOp
                val hasGlobalActionPerm = manager.checkPermission(p, actionNode)

                if (!hasItemSpecificPerm || !hasGlobalActionPerm) {
                    p.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการดำเนินการนี้"))
                    return@setButton
                }

                val confirmEnabled = manager.shopConfig?.getBoolean("settings.confirm-transaction", true) ?: true
                
                if (confirmEnabled) {
                    openTransactionMenu(p, item, section, isBuy, finalBuy, finalSell)
                } else {
                    executeTransaction(p, item, section, 1, isBuy, finalBuy, finalSell)
                    openCategoryMenu(p, sectionId, pageNum)
                }
            }
        }

        // แถบนำทางด้านล่างสุด (Navigation Bar)
        val lastRowStart = size - 9
        
        // ปุ่มกลับเมนูหลัก
        val backToMainItem = ItemStack(Material.BARRIER)
        val backMeta = backToMainItem.itemMeta
        if (backMeta != null) {
            backMeta.displayName(core.luminaworld.utils.ColorParser.parse(manager.getMsg("back-to-main-menu", "&cกลับเมนูหลัก")))
            backToMainItem.itemMeta = backMeta
        }
        gui.setButton(lastRowStart + 4, backToMainItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openMainMenu(p)
        }

        // ปุ่มย้อนกลับหน้า
        if (pageNum > 1) {
            val prevPageItem = ItemStack(Material.ARROW)
            val prevMeta = prevPageItem.itemMeta
            if (prevMeta != null) {
                prevMeta.displayName(core.luminaworld.utils.ColorParser.parse(
                    manager.getMsg("previous-page", "&eย้อนกลับไปหน้า %page%").replace("%page%", (pageNum - 1).toString())
                ))
                prevPageItem.itemMeta = prevMeta
            }
            gui.setButton(lastRowStart + 2, prevPageItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openCategoryMenu(p, sectionId, pageNum - 1)
            }
        }

        // ปุ่มหน้าถัดไป
        if (shopPages.containsKey(pageNum + 1)) {
            val nextPageItem = ItemStack(Material.ARROW)
            val nextMeta = nextPageItem.itemMeta
            if (nextMeta != null) {
                nextMeta.displayName(core.luminaworld.utils.ColorParser.parse(
                    manager.getMsg("next-page", "&eหน้าถัดไป %page%").replace("%page%", (pageNum + 1).toString())
                ))
                nextPageItem.itemMeta = nextMeta
            }
            gui.setButton(lastRowStart + 6, nextPageItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openCategoryMenu(p, sectionId, pageNum + 1)
            }
        }

        gui.open(player)
    }

    /**
     * 3. หน้าต่างยืนยันและเลือกจำนวนธุรกรรม (Transaction Screen - 45 Slots Authentic EconomyShopGUI Layout)
     */
    fun openTransactionMenu(
        player: Player,
        item: ShopItem,
        section: ShopSection,
        isBuy: Boolean,
        currentBuy: Double?,
        currentSell: Double?,
        quantity: Int = 1
    ) {
        val manager = ShopManager.instance
        val titlePattern = if (isBuy) manager.getMsg("inventory-howmuchbuy-title", "&8ซื้อ: %item%") else manager.getMsg("inventory-howmuchsell-title", "&8ขาย: %item%")
        val titleStr = titlePattern.replace("%item%", item.name ?: item.material)
        val gui = LuminaGUI(plugin, core.luminaworld.utils.ColorParser.parse(titleStr), 45)

        val fillItem = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val fillMeta = fillItem.itemMeta
        if (fillMeta != null) {
            fillMeta.displayName(core.luminaworld.utils.ColorParser.parse(" "))
            fillItem.itemMeta = fillMeta
        }
        gui.fill(fillItem)

        val ecoType = section.economy ?: "VAULT"
        val formatter = EconomyBridgeService.instance.parseEconomy(ecoType)
        val templateStack = manager.createItemStack(item)
        val maxStackSize = templateStack.maxStackSize

        var currentQty = quantity.coerceIn(1, 2304)

        // Helper สร้าง Item สำหรับ Slot 13 (Confirm Button)
        fun buildConfirmItem(qty: Int): ItemStack {
            val totalCost = calculateTotalTransactionPrice(item, section, qty, isBuy)
            val confirmItem = ItemStack(Material.PAPER)
            val confirmMeta = confirmItem.itemMeta
            if (confirmMeta != null) {
                val actionTitle = if (isBuy) manager.getMsg("confirm-buy-button", "&aยืนยันการสั่งซื้อ") else manager.getMsg("confirm-sell-button", "&aยืนยันการขาย")
                confirmMeta.displayName(core.luminaworld.utils.ColorParser.parse(actionTitle))
                confirmMeta.lore(listOf(
                    core.luminaworld.utils.ColorParser.parse(manager.getMsg("transaction-amount-lore", "&7จำนวน: &e%amount% &7ชิ้น").replace("%amount%", qty.toString())),
                    core.luminaworld.utils.ColorParser.parse(manager.getMsg("transaction-total-price-lore", "&7ราคารวม: &a%price%").replace("%price%", formatter.format(totalCost))),
                    core.luminaworld.utils.ColorParser.parse(""),
                    core.luminaworld.utils.ColorParser.parse(manager.getMsg("click-to-confirm-lore", "&eคลิกเพื่อยืนยันทำรายการ"))
                ))
                confirmItem.itemMeta = confirmMeta
            }
            return confirmItem
        }

        // Helper สร้าง Item สำหรับ Slot 22 (Selected Item - Display Only)
        fun buildSelectedItem(qty: Int): ItemStack {
            val totalCost = calculateTotalTransactionPrice(item, section, qty, isBuy)
            val selectedItem = manager.createItemStack(item)
            selectedItem.amount = Math.max(1, Math.min(qty, 64))
            val selMeta = selectedItem.itemMeta
            if (selMeta != null) {
                val lore = selMeta.lore()?.toMutableList() ?: ArrayList()
                lore.add(core.luminaworld.utils.ColorParser.parse(""))
                lore.add(core.luminaworld.utils.ColorParser.parse(manager.getMsg("selected-amount-lore", "&7จำนวนที่เลือก: &e%amount% &7ชิ้น").replace("%amount%", qty.toString())))
                lore.add(core.luminaworld.utils.ColorParser.parse(manager.getMsg("transaction-total-price-lore", "&7ราคารวม: &a%price%").replace("%price%", formatter.format(totalCost))))
                lore.add(core.luminaworld.utils.ColorParser.parse(""))
                lore.add(core.luminaworld.utils.ColorParser.parse("&7(กดปุ่มกระดาษด้านบนเพื่อยืนยัน)"))
                selMeta.lore(lore)
                selectedItem.itemMeta = selMeta
            }
            return selectedItem
        }

        // Helper สร้าง Item สำหรับ Slot 31 (Chest Stack Button)
        fun buildChestItem(qty: Int): ItemStack {
            val totalStackQty = qty * maxStackSize
            val totalStackCost = calculateTotalTransactionPrice(item, section, totalStackQty, isBuy)
            val chestItem = ItemStack(Material.CHEST)
            val chestMeta = chestItem.itemMeta
            if (chestMeta != null) {
                val chestTitle = if (isBuy) {
                    manager.getMsg("buy-stacks-button", "&eซื้อแบบ Stack (%stacks% กอง)").replace("%stacks%", qty.toString())
                } else {
                    manager.getMsg("sell-stacks-button", "&eขายแบบ Stack (%stacks% กอง)").replace("%stacks%", qty.toString())
                }
                chestMeta.displayName(core.luminaworld.utils.ColorParser.parse(chestTitle))
                chestMeta.lore(listOf(
                    core.luminaworld.utils.ColorParser.parse(
                        manager.getMsg("stack-amount-lore", "&7จำนวน: &e%stacks% &7กอง &8(%total% ชิ้น)")
                            .replace("%stacks%", qty.toString()).replace("%total%", totalStackQty.toString())
                    ),
                    core.luminaworld.utils.ColorParser.parse(
                        manager.getMsg("stack-total-price-lore", "&7ราคารวม: &a%price%")
                            .replace("%price%", formatter.format(totalStackCost))
                    ),
                    core.luminaworld.utils.ColorParser.parse(""),
                    core.luminaworld.utils.ColorParser.parse(
                        manager.getMsg("click-to-confirm-stack-lore", "&eคลิกเพื่อทำรายการ %stacks% กอง (%total% ชิ้น)")
                            .replace("%stacks%", qty.toString()).replace("%total%", totalStackQty.toString())
                    )
                ))
                chestItem.itemMeta = chestMeta
            }
            return chestItem
        }

        // ฟังก์ชัน Dynamic In-place Update อัปเดต GUI โดยไม่ต้อง re-open inventory
        val updateQty: (Player, Int) -> Unit = { p, newQty ->
            currentQty = newQty.coerceIn(1, 2304)
            val topInv = p.openInventory.topInventory
            val holder = topInv.holder as? LuminaGUIHolder
            val isCurrentGUI = holder != null && holder.gui == gui

            val newConfirm = buildConfirmItem(currentQty)
            val newSelected = buildSelectedItem(currentQty)
            val newChest = buildChestItem(currentQty)

            if (isCurrentGUI) {
                topInv.setItem(13, newConfirm)
                topInv.setItem(22, newSelected)
                topInv.setItem(31, newChest)
            }

            // อัปเดตผูก handler บน GUI object ด้วย
            gui.setButton(13, newConfirm) { playerRef, _ ->
                playerRef.playSound(playerRef.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)
                executeTransaction(playerRef, item, section, currentQty, isBuy, currentBuy, currentSell)
                playerRef.closeInventory()
            }
            gui.setButton(22, newSelected) { playerRef, _ ->
                playerRef.playSound(playerRef.location, Sound.UI_BUTTON_CLICK, 0.3f, 1.2f)
            }
            gui.setButton(31, newChest) { playerRef, _ ->
                playerRef.playSound(playerRef.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)
                executeTransaction(playerRef, item, section, currentQty * maxStackSize, isBuy, currentBuy, currentSell)
                playerRef.closeInventory()
            }
        }

        // 1. Slot 13: Paper (ปุ่มยืนยันทำรายการ)
        val initialConfirmItem = buildConfirmItem(currentQty)
        gui.setButton(13, initialConfirmItem) { p, _ ->
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)
            executeTransaction(p, item, section, currentQty, isBuy, currentBuy, currentSell)
            p.closeInventory()
        }

        // 2. Slot 19, 20, 21: Magenta Glass Panes (ลดจำนวน -32, -16, -1)
        val decAmounts = listOf(-32 to 19, -16 to 20, -1 to 21)
        for ((diff, slotIdx) in decAmounts) {
            val paneAmount = Math.max(1, Math.min(Math.abs(diff), 64))
            val magPane = ItemStack(Material.MAGENTA_STAINED_GLASS_PANE, paneAmount)
            val magMeta = magPane.itemMeta
            if (magMeta != null) {
                magMeta.displayName(core.luminaworld.utils.ColorParser.parse(
                    manager.getMsg("decrease-amount-button", "&c%amount% ชิ้น").replace("%amount%", diff.toString())
                ))
                magPane.itemMeta = magMeta
            }
            gui.setButton(slotIdx, magPane) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                updateQty(p, currentQty + diff)
            }
        }

        // 3. Slot 22: Selected Item (ไอเทมที่เลือก - Display Only ป้องกัน Bedrock แต้มสั่งซื้อหลุด)
        val initialSelectedItem = buildSelectedItem(currentQty)
        gui.setButton(22, initialSelectedItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.3f, 1.2f)
        }

        // 4. Slot 23, 24, 25: Light Blue Glass Panes (เพิ่มจำนวน +1, +16, +32)
        val incAmounts = listOf(1 to 23, 16 to 24, 32 to 25)
        for ((diff, slotIdx) in incAmounts) {
            val paneAmount = Math.max(1, Math.min(diff, 64))
            val lightPane = ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE, paneAmount)
            val lightMeta = lightPane.itemMeta
            if (lightMeta != null) {
                lightMeta.displayName(core.luminaworld.utils.ColorParser.parse(
                    manager.getMsg("increase-amount-button", "&a+%amount% ชิ้น").replace("%amount%", diff.toString())
                ))
                lightPane.itemMeta = lightMeta
            }
            gui.setButton(slotIdx, lightPane) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                updateQty(p, currentQty + diff)
            }
        }

        // 5. Slot 31: Chest (ปุ่มซื้อ/ขายแบบ Stack)
        val initialChestItem = buildChestItem(currentQty)
        gui.setButton(31, initialChestItem) { p, _ ->
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)
            executeTransaction(p, item, section, currentQty * maxStackSize, isBuy, currentBuy, currentSell)
            p.closeInventory()
        }

        // 6. Slot 40: Barrier (ปุ่มย้อนกลับ)
        val backItem = ItemStack(Material.BARRIER)
        val backMeta = backItem.itemMeta
        if (backMeta != null) {
            backMeta.displayName(core.luminaworld.utils.ColorParser.parse(manager.getMsg("back-button", "&cย้อนกลับ")))
            backItem.itemMeta = backMeta
        }
        gui.setButton(40, backItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openCategoryMenu(p, item.sectionId, 1)
        }

        gui.open(player)
    }

    private fun createTransactionButtonItem(
        mat: Material,
        qty: Int,
        actionName: String,
        totalCost: Double,
        formatter: core.luminaworld.modules.system.EconomyBridge.EconomyProvider
    ): ItemStack {
        val itemStack = ItemStack(mat, min(qty, 64))
        val meta = itemStack.itemMeta ?: return itemStack
        meta.displayName(core.luminaworld.utils.ColorParser.parse("&a&l$actionName &e&l$qty ชิ้น"))
        meta.lore(listOf(
            core.luminaworld.utils.ColorParser.parse("&7ราคารวม: &f${formatter.format(totalCost)}")
        ))
        itemStack.itemMeta = meta
        return itemStack
    }

    /**
     * 4. หน้าต่างโยนไอเทมเพื่อขายแบบด่วน (SellGUI)
     */
    fun openSellGUI(player: Player) {
        val manager = ShopManager.instance
        val defaultTitle = manager.getMsg("inventory-sellgui-title", "&8โยนไอเทมเพื่อขายทั้งหมด")
        val titleStr = manager.shopConfig?.getString("settings.menu-titles.sell-gui", defaultTitle) ?: defaultTitle
        
        val gui = LuminaGUI(plugin, core.luminaworld.utils.ColorParser.parse(titleStr), 54, cancelClicks = false)
        
        // แถบตกแต่งล่างสุด (NavBar - Slots 45 to 53)
        val fillPane = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val paneMeta = fillPane.itemMeta
        if (paneMeta != null) {
            paneMeta.displayName(core.luminaworld.utils.ColorParser.parse(" "))
            fillPane.itemMeta = paneMeta
        }
        for (s in 45..53) {
            if (s != 49) {
                gui.setButton(s, fillPane) { _, _ -> }
            }
        }

        // ปุ่มดาวตรงกลาง (Nether Star - Slot 49)
        val starItem = ItemStack(Material.NETHER_STAR)
        val starMeta = starItem.itemMeta
        if (starMeta != null) {
            starMeta.displayName(core.luminaworld.utils.ColorParser.parse(manager.getMsg("sellgui-star-button", "&a&lSellGUI")))
            starMeta.lore(listOf(
                core.luminaworld.utils.ColorParser.parse(manager.getMsg("sellgui-star-lore-1", "&6วางไอเทมที่ต้องการขายลงในเมนูนี้")),
                core.luminaworld.utils.ColorParser.parse(manager.getMsg("sellgui-star-lore-2", "&6เมื่อปิดหน้าต่าง ไอเทมจะถูกขายทั้งหมดโดยอัตโนมัติ")),
                core.luminaworld.utils.ColorParser.parse(manager.getMsg("sellgui-star-lore-3", "&eไอเทมที่ไม่สามารถขายได้")),
                core.luminaworld.utils.ColorParser.parse(manager.getMsg("sellgui-star-lore-4", "&eจะถูกคืนกลับเข้ากระเป๋าของคุณ"))
            ))
            starItem.itemMeta = starMeta
        }
        gui.setButton(49, starItem) { _, _ -> }

        gui.onClose = { p, closedInv ->
            var totalEarnings = 0.0
            var itemsSold = 0
            val economyTypeMap = HashMap<String, Double>()

            // สแกนเฉพาะสล็อต 0 ถึง 44 (ไม่รวมแถบ Navigation Bar สล็อต 45..53)
            for (i in 0 until 45) {
                val isStack = closedInv.getItem(i)
                if (isStack != null && isStack.type != Material.AIR) {
                    val shopItem = findShopItemByItemStack(isStack)
                    if (shopItem != null) {
                        val section = manager.sections[shopItem.sectionId.lowercase()]
                        if (section != null) {
                            // คำนวณราคาขายสะสมของยอดชิ้นนั้น
                            val finalSell = calculateTotalTransactionPrice(shopItem, section, isStack.amount, isBuy = false)
                            if (finalSell > 0.0) {
                                val eco = section.economy ?: "VAULT"
                                
                                if (EconomyBridgeService.instance.deposit(p, eco, finalSell)) {
                                    if (section.dpEnabled) {
                                        DynamicPricingService.instance.recordTransaction(shopItem.id, isStack.amount, isBuy = false)
                                    }
                                    
                                    economyTypeMap[eco] = (economyTypeMap[eco] ?: 0.0) + finalSell
                                    totalEarnings += finalSell
                                    itemsSold += isStack.amount
                                    closedInv.setItem(i, null)
                                }
                            }
                        }
                    }
                }
            }

            if (itemsSold > 0) {
                p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f)
                
                val msgDetails = economyTypeMap.entries.joinToString(", ") { entry ->
                    val formatter = EconomyBridgeService.instance.parseEconomy(entry.key)
                    formatter.format(entry.value)
                }
                
                p.sendMessage(manager.getMsg("items-sold-successfully", "&aขายไอเทมสำเร็จ &e%amount% ชิ้น &aได้รับเงินรวม &e%price%")
                    .replace("%amount%", itemsSold.toString())
                    .replace("%price%", msgDetails))

                manager.sendDiscordShopWebhook(p, "sold", "หลายรายการ (/sellgui)", itemsSold, msgDetails)
            }

            // คืนไอเทมที่เหลือกลับตัวผู้เล่น (เฉพาะสล็อต 0 ถึง 44)
            for (i in 0 until 45) {
                val item = closedInv.getItem(i)
                if (item != null && item.type != Material.AIR) {
                    val leftovers = p.inventory.addItem(item)
                    if (leftovers.isNotEmpty()) {
                        for (left in leftovers.values) {
                            p.world.dropItem(p.location, left)
                        }
                    }
                }
            }
        }

        gui.open(player)
    }

    /**
     * ดำเนินการทำธุรกรรม ซื้อ / ขาย ไอเทม
     */
    fun executeTransaction(player: Player, item: ShopItem, section: ShopSection, qty: Int, isBuy: Boolean, finalBuy: Double?, finalSell: Double?) {
        val manager = ShopManager.instance

        val actionNode = if (isBuy) "item-buy" else "item-sell"
        val hasItemSpecificPerm = item.permission.isNullOrBlank() || player.hasPermission(item.permission) || player.isOp
        val hasGlobalActionPerm = manager.checkPermission(player, actionNode)

        if (!hasItemSpecificPerm || !hasGlobalActionPerm) {
            player.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการดำเนินการนี้"))
            return
        }

        val ecoType = section.economy ?: "VAULT"
        val formatter = EconomyBridgeService.instance.parseEconomy(ecoType)

        // สร้างไอเทมต้นแบบของร้านค้าเพื่อตรวจสอบความเหมือน
        val templateItem = manager.createItemStack(item)

        if (isBuy) {
            if (finalBuy == null || finalBuy <= 0.0) {
                player.sendMessage(manager.getMsg("cannot-buy-this-item", "&cไอเทมนี้ไม่เปิดขายในร้านค้า"))
                return
            }

            // คำนวณราคาแบบสะสมล็อต (Bulk pricing) เพื่อไม่ให้หลบเลี่ยงความผันแปร
            val totalCost = calculateTotalTransactionPrice(item, section, qty, isBuy = true)
            val balance = EconomyBridgeService.instance.getBalance(player, ecoType)
            if (balance < totalCost) {
                player.sendMessage(manager.getMsg("insufficient-funds", "&cคุณมีเงินในบัญชีไม่เพียงพอสำหรับการทำธุรกรรมนี้"))
                return
            }

            // ตรวจสอบพื้นที่เก็บของอิงตาม maxStackSize จริงของไอเทม (Inventory Space Bug Fix)
            val productStack = templateItem.clone()
            productStack.amount = qty
            
            var spaceAvailable = 0
            val maxStack = productStack.maxStackSize
            for (slot in 0..35) {
                val invItem = player.inventory.getItem(slot)
                if (invItem == null || invItem.type == Material.AIR) {
                    spaceAvailable += maxStack
                } else if (invItem.isSimilar(productStack)) {
                    spaceAvailable += (maxStack - invItem.amount).coerceAtLeast(0)
                }
            }

            if (spaceAvailable < qty) {
                player.sendMessage(manager.getMsg("not-enough-space-inside-inventory", "&cช่องเก็บของของคุณเต็ม ไม่สามารถรับสินค้าได้"))
                return
            }

            if (EconomyBridgeService.instance.withdraw(player, ecoType, totalCost)) {
                if (section.dpEnabled) {
                    DynamicPricingService.instance.recordTransaction(item.id, qty, isBuy = true)
                }

                if (item.runCommands != null && item.runCommands.isNotEmpty()) {
                    for (cmd in item.runCommands) {
                        val formatted = cmd.replace("%player%", player.name).replace("%amount%", qty.toString())
                        if (formatted.startsWith("/")) {
                            player.performCommand(formatted.substring(1))
                        } else {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted)
                        }
                    }
                } else {
                    val leftovers = player.inventory.addItem(productStack)
                    for (left in leftovers.values) {
                        player.world.dropItem(player.location, left)
                    }
                }

                val formattedPrice = formatter.format(totalCost)
                player.sendMessage(manager.getMsg("bought-item-successfully", "&aซื้อของสำเร็จ &e%amount%x %item% &aในราคา &e%price%")
                    .replace("%amount%", qty.toString())
                    .replace("%item%", item.name ?: item.material)
                    .replace("%price%", formattedPrice))

                manager.sendDiscordShopWebhook(player, "bought", item.name ?: item.material, qty, formattedPrice)
            }
        } else {
            if (finalSell == null || finalSell <= 0.0) {
                player.sendMessage(manager.getMsg("cannot-sell-this-item", "&cไอเทมนี้ไม่รับซื้อในร้านค้า"))
                return
            }

            val totalEarnings = calculateTotalTransactionPrice(item, section, qty, isBuy = false)

            // ตรวจสอบไอเทมของผู้เล่นด้วย isSimilar ป้องกัน Item Deletion Exploit
            var ownedQty = 0
            for (slot in 0..35) {
                val invItem = player.inventory.getItem(slot)
                if (invItem != null && isSimilarShopItem(invItem, templateItem)) {
                    ownedQty += invItem.amount
                }
            }

            if (ownedQty < qty) {
                player.sendMessage(manager.getMsg("not-enough-items-to-sell", "&cคุณไม่มีไอเทมเพียงพอในช่องเก็บของสำหรับการขาย"))
                return
            }

            // ดำเนินการลบไอเทมที่ยืนยันว่าเหมือนจริง
            var toRemove = qty
            for (slot in 0..35) {
                val invItem = player.inventory.getItem(slot)
                if (invItem != null && isSimilarShopItem(invItem, templateItem)) {
                    if (invItem.amount > toRemove) {
                        invItem.amount -= toRemove
                        break
                    }
                    toRemove -= invItem.amount
                    player.inventory.setItem(slot, null)
                    if (toRemove <= 0) break
                }
            }

            if (EconomyBridgeService.instance.deposit(player, ecoType, totalEarnings)) {
                if (section.dpEnabled) {
                    DynamicPricingService.instance.recordTransaction(item.id, qty, isBuy = false)
                }

                val formattedPrice = formatter.format(totalEarnings)
                player.sendMessage(manager.getMsg("sold-item-successfully", "&aขายของสำเร็จ &e%amount%x %item% &aได้รับเงิน &e%price%")
                    .replace("%amount%", qty.toString())
                    .replace("%item%", item.name ?: item.material)
                    .replace("%price%", formattedPrice))

                manager.sendDiscordShopWebhook(player, "sold", item.name ?: item.material, qty, formattedPrice)
            }
        }
    }

    /**
     * เปรียบเทียบความคล้ายคลึงของไอเทมอย่างแม่นยำ (Material, CustomModelData, DisplayName)
     */
    fun isSimilarShopItem(stack: ItemStack, template: ItemStack): Boolean {
        if (stack.type != template.type) return false
        val meta1 = stack.itemMeta
        val meta2 = template.itemMeta
        if (meta1 == null && meta2 == null) return true
        if (meta1 == null || meta2 == null) return false

        // ตรวจสอบ Custom Model Data
        val cmd1 = if (meta1.hasCustomModelData()) meta1.customModelData else null
        val cmd2 = if (meta2.hasCustomModelData()) meta2.customModelData else null
        if (cmd1 != cmd2) return false

        // ตรวจสอบ DisplayName เฉพาะกรณีที่ template มีการกำหนด DisplayName
        if (meta2.hasDisplayName()) {
            if (!meta1.hasDisplayName()) return false
            if (meta1.displayName() != meta2.displayName()) return false
        }

        return true
    }

    /**
     * ค้นหารหัสสินค้าจากกระเป๋าผู้เล่น (เทียบรายละเอียดเต็มรูปแบบ)
     */
    fun findShopItemByItemStack(isStack: ItemStack): ShopItem? {
        val manager = ShopManager.instance
        val matName = isStack.type.toString().lowercase()
        
        for (section in manager.shops.values) {
            for (page in section.values) {
                for (item in page.items.values) {
                    if (item.material.lowercase() == matName) {
                        val template = manager.createItemStack(item)
                        if (isSimilarShopItem(isStack, template)) {
                            return item
                        }
                    }
                }
            }
        }
        return null
    }

    // คำนวณผลรวมธุรกรรมแบบ Dynamic สะสมชิ้นต่อชิ้น (ป้องกัน Bulk Exploit และคุมเพดานราคาป้องกัน Arbitrage)
    fun calculateTotalTransactionPrice(item: ShopItem, section: ShopSection, qty: Int, isBuy: Boolean): Double {
        val basePrice = if (isBuy) item.buyPrice else item.sellPrice
        if (basePrice == null || basePrice <= 0.0) return 0.0
        if (!section.dpEnabled) return basePrice * qty

        val dpService = DynamicPricingService.instance
        val currentNet = dpService.getNetTransactions(item.id)
        var total = 0.0

        for (i in 0 until qty) {
            val simulatedNet = if (isBuy) currentNet + i else currentNet - i
            
            if (isBuy) {
                val multiplier = 1.0 + (simulatedNet.toDouble() / section.dpMaxStock.toDouble())
                val calculatedPrice = basePrice * multiplier
                val maxPrice = basePrice * (1.0 + (section.dpBuyPriceScaling / 100.0))
                val minPrice = basePrice * 0.5
                val finalBuyItem = max(minPrice, min(maxPrice, calculatedPrice))
                total += finalBuyItem
            } else {
                val multiplier = 1.0 + (simulatedNet.toDouble() / section.dpMaxStock.toDouble())
                val calculatedPrice = basePrice * multiplier
                val minPrice = basePrice * (section.dpSellPriceScaling / 100.0)
                val maxPrice = basePrice * 2.0
                var finalSellItem = max(minPrice, min(maxPrice, calculatedPrice))
                
                // คุมเพดานราคารายชิ้นเฉพาะเมื่อไอเทมเปิดให้ซื้อ (Arbitrage Prevention)
                val rawBuy = item.buyPrice
                if (rawBuy != null && rawBuy > 0.0) {
                    val simulatedBuyNet = simulatedNet
                    val buyMultiplier = 1.0 + (simulatedBuyNet.toDouble() / section.dpMaxStock.toDouble())
                    val calcBuy = rawBuy * buyMultiplier
                    val maxBuy = rawBuy * (1.0 + (section.dpBuyPriceScaling / 100.0))
                    val minBuy = rawBuy * 0.5
                    val finalBuyItem = max(minBuy, min(maxBuy, calcBuy))
                    finalSellItem = min(finalSellItem, finalBuyItem * 0.8)
                }
                total += finalSellItem
            }
        }
        return total
    }

    // ดึงราคาซื้อผันผวนรายเดี่ยว
    fun getBuyPrice(item: ShopItem, section: ShopSection): Double? {
        val base = item.buyPrice ?: return null
        if (base <= 0.0) return null
        if (!section.dpEnabled) return base
        return DynamicPricingService.instance.getModifiedBuyPrice(item.id, base, section.dpMaxStock, section.dpBuyPriceScaling)
    }

    // ดึงราคาขายผันผวนรายเดี่ยว (ป้องกัน Arbitrage เฉพาะไอเทมที่ซื้อได้)
    fun getSellPrice(item: ShopItem, section: ShopSection): Double? {
        val base = item.sellPrice ?: return null
        if (base <= 0.0) return null
        val calculatedSell = if (!section.dpEnabled) base else {
            DynamicPricingService.instance.getModifiedSellPrice(item.id, base, section.dpMaxStock, section.dpSellPriceScaling)
        }
        val buyPrice = getBuyPrice(item, section)
        if (buyPrice != null && buyPrice > 0.0) {
            return min(calculatedSell, buyPrice * 0.8)
        }
        return calculatedSell
    }
}
