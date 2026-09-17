package core.luminaworld.modules.features.Shop

import core.luminaworld.LuminaCore
import core.luminaworld.modules.system.EconomyBridge.EconomyBridgeService
import core.luminaworld.modules.system.DynamicPricing.DynamicPricingService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale

class ShopCommands(private val plugin: LuminaCore) : CommandExecutor, TabCompleter {

    fun register() {
        val manager = plugin.commandManager ?: return
        manager.registerCommand("shop", this, this)
        manager.registerCommand("sellall", this, this)
        manager.registerCommand("sellgui", this, this)
        manager.registerCommand("shopgive", this, this)
        manager.registerCommand("sreload", this, this)
    }

    fun unregister() {
        val manager = plugin.commandManager ?: return
        manager.unregisterCommand("shop")
        manager.unregisterCommand("sellall")
        manager.unregisterCommand("sellgui")
        manager.unregisterCommand("shopgive")
        manager.unregisterCommand("sreload")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val manager = ShopManager.instance
        val cmdName = command.name.lowercase(Locale.ENGLISH)

        if (cmdName == "sreload") {
            val reloadPerm = manager.shopConfig?.getString("permissions.admin.reload-permission", "luminashop.admin.reload") ?: "luminashop.admin.reload"
            if (sender is Player && !sender.hasPermission(reloadPerm) && !sender.isOp) {
                sender.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการเข้าถึงคำสั่งนี้"))
                return true
            }
            manager.loadConfigs()
            sender.sendMessage(manager.getMsg("config-reloaded", "&aรีโหลดระบบร้านค้าและข้อมูลการตั้งค่าเสร็จสิ้นแล้ว!"))
            return true
        }

        if (cmdName == "shopgive") {
            val givePerm = manager.shopConfig?.getString("permissions.admin.give-permission", "luminashop.admin.give") ?: "luminashop.admin.give"
            if (sender is Player && !sender.hasPermission(givePerm) && !sender.isOp) {
                sender.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการเข้าถึงคำสั่งนี้"))
                return true
            }
            if (args.size < 2) {
                sender.sendMessage("§cการใช้งาน: /shopgive <ผู้เล่น> <ไอดีไอเทม> [จำนวน]")
                return true
            }
            val target = Bukkit.getPlayer(args[0])
            if (target == null) {
                sender.sendMessage("§cไม่พบผู้เล่นคนดังกล่าว")
                return true
            }
            val itemId = args[1]
            val amount = if (args.size > 2) args[2].toIntOrNull() ?: 1 else 1

            val shopItem = findShopItemById(itemId)
            if (shopItem == null) {
                sender.sendMessage("§cไม่พบรหัสสินค้า '$itemId' ในระบบร้านค้า")
                return true
            }

            val itemStack = manager.createItemStack(shopItem)
            itemStack.amount = amount
            val leftovers = target.inventory.addItem(itemStack)
            for (left in leftovers.values) {
                target.world.dropItem(target.location, left)
            }
            sender.sendMessage("§aส่งมอบ ${amount}x ${shopItem.name ?: shopItem.material} ให้กับ ${target.name} สำเร็จ")
            return true
        }

        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("§cคำสั่งนี้สามารถรันได้เฉพาะผู้เล่นในเกมเท่านั้น")
            return true
        }

        when (cmdName) {
            "shop" -> {
                if (!manager.checkPermission(player, "shop-use")) {
                    player.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการเข้าถึงคำสั่งนี้"))
                    return true
                }
                if (args.isNotEmpty()) {
                    val category = args[0].lowercase(Locale.ENGLISH)
                    val matchedSection = manager.sections[category]
                    if (matchedSection != null && matchedSection.enabled) {
                        if (!manager.checkSectionPermission(player, matchedSection.id)) {
                            player.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการเข้าถึงหมวดหมู่นี้"))
                            return true
                        }
                        ShopGUIManager.instance.openCategoryMenu(player, matchedSection.id, 1)
                        return true
                    }
                }
                ShopGUIManager.instance.openMainMenu(player)
            }
            "sellgui" -> {
                if (!manager.checkPermission(player, "sellgui")) {
                    player.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการเข้าถึงคำสั่งนี้"))
                    return true
                }
                ShopGUIManager.instance.openSellGUI(player)
            }
            "sellall" -> {
                val handMode = args.isNotEmpty() && args[0].equals("hand", ignoreCase = true)
                val nodeKey = if (handMode) "sellall-hand" else "sellall"
                
                if (!manager.checkPermission(player, nodeKey)) {
                    player.sendMessage(manager.getMsg("no-permission", "&cคุณไม่มีสิทธิ์ในการเข้าถึงคำสั่งนี้"))
                    return true
                }
                
                if (handMode) {
                    val handItem = player.inventory.itemInMainHand
                    if (handItem.type == Material.AIR) {
                        player.sendMessage(manager.getMsg("no-item-in-hand", "&cคุณไม่มีไอเทมในมือหลักเพื่อทำรายการขาย"))
                        return true
                    }
                    val shopItem = ShopGUIManager.instance.findShopItemByItemStack(handItem)
                    if (shopItem == null) {
                        player.sendMessage(manager.getMsg("cannot-sell-this-item", "&cไอเทมนี้ไม่รับซื้อในร้านค้า"))
                        return true
                    }
                    val section = manager.sections[shopItem.sectionId.lowercase(Locale.ENGLISH)]
                    if (section == null) return true
                    val finalSell = ShopGUIManager.instance.getSellPrice(shopItem, section)
                    if (finalSell == null || finalSell <= 0.0) {
                        player.sendMessage(manager.getMsg("cannot-sell-this-item", "&cไอเทมนี้ไม่รับซื้อในร้านค้า"))
                        return true
                    }

                    val qty = handItem.amount
                    val cost = finalSell * qty
                    val eco = section.economy ?: "VAULT"

                    if (EconomyBridgeService.instance.deposit(player, eco, cost)) {
                        if (section.dpEnabled) {
                            DynamicPricingService.instance.recordTransaction(shopItem.id, qty, isBuy = false)
                        }
                        player.inventory.setItemInMainHand(null)
                        val formatter = EconomyBridgeService.instance.parseEconomy(eco)
                        val formattedCost = formatter.format(cost)
                        player.sendMessage(manager.getMsg("sold-item-successfully", "&aขายของสำเร็จ &e%amount%x %item% &aได้รับเงิน &e%price%")
                            .replace("%amount%", qty.toString())
                            .replace("%item%", shopItem.name ?: shopItem.material)
                            .replace("%price%", formattedCost))

                        manager.sendDiscordShopWebhook(player, "sold", shopItem.name ?: shopItem.material, qty, formattedCost)
                    }
                } else {
                    var itemsSold = 0
                    val economyTypeMap = HashMap<String, Double>()

                    for (i in 0..35) {
                        val isStack = player.inventory.getItem(i)
                        if (isStack != null && isStack.type != Material.AIR) {
                            val shopItem = ShopGUIManager.instance.findShopItemByItemStack(isStack)
                            if (shopItem != null) {
                                val section = manager.sections[shopItem.sectionId.lowercase(Locale.ENGLISH)]
                                if (section != null) {
                                    val finalSell = ShopGUIManager.instance.getSellPrice(shopItem, section)
                                    if (finalSell != null && finalSell > 0.0) {
                                        val cost = finalSell * isStack.amount
                                        val eco = section.economy ?: "VAULT"
                                        
                                        if (EconomyBridgeService.instance.deposit(player, eco, cost)) {
                                            if (section.dpEnabled) {
                                                DynamicPricingService.instance.recordTransaction(shopItem.id, isStack.amount, isBuy = false)
                                            }
                                            economyTypeMap[eco] = (economyTypeMap[eco] ?: 0.0) + cost
                                            itemsSold += isStack.amount
                                            player.inventory.setItem(i, null)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (itemsSold > 0) {
                        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f)
                        val msgDetails = economyTypeMap.entries.joinToString(", ") { entry ->
                            val formatter = EconomyBridgeService.instance.parseEconomy(entry.key)
                            formatter.format(entry.value)
                        }
                        player.sendMessage(manager.getMsg("items-sold-successfully", "&aขายไอเทมสำเร็จ &e%amount% ชิ้น &aได้รับเงินรวม &e%price%")
                            .replace("%amount%", itemsSold.toString())
                            .replace("%price%", msgDetails))

                        manager.sendDiscordShopWebhook(player, "sold", "หลายรายการ (/sellall)", itemsSold, msgDetails)
                    } else {
                        player.sendMessage(manager.getMsg("no-sellable-items", "&cไม่พบไอเทมที่สามารถขายเข้าร้านค้าได้ในกระเป๋าของคุณ"))
                    }
                }
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val manager = ShopManager.instance
        val cmdName = command.name.lowercase(Locale.ENGLISH)

        if (cmdName == "shop" && args.size == 1) {
            val query = args[0].lowercase(Locale.ENGLISH)
            return manager.sections.values
                .filter { it.enabled && !it.hidden && !it.subSection && it.id.lowercase(Locale.ENGLISH).startsWith(query) }
                .map { it.id }
        }

        if (cmdName == "sellall" && args.size == 1) {
            return listOf("hand").filter { it.startsWith(args[0].lowercase(Locale.ENGLISH)) }
        }

        if (cmdName == "shopgive") {
            if (args.size == 1) {
                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase(Locale.ENGLISH).startsWith(args[0].lowercase(Locale.ENGLISH)) }
            }
            if (args.size == 2) {
                val query = args[1].lowercase(Locale.ENGLISH)
                val list = ArrayList<String>()
                for (section in manager.shops.values) {
                    for (page in section.values) {
                        for (item in page.items.values) {
                            if (item.id.lowercase(Locale.ENGLISH).startsWith(query)) {
                                list.add(item.id)
                            }
                        }
                    }
                }
                return list.take(30)
            }
        }

        return emptyList()
    }

    private fun findShopItemById(itemId: String): ShopItem? {
        val manager = ShopManager.instance
        val parts = itemId.split(".")
        if (parts.size < 2) return null
        
        val secId = parts[0].lowercase(Locale.ENGLISH)
        val itemKey = parts[1]
        
        val pageMap = manager.shops[secId] ?: return null
        for (page in pageMap.values) {
            val slot = itemKey.toIntOrNull()
            if (slot != null) {
                val item = page.items[slot]
                if (item != null) return item
            } else {
                val item = page.items.values.find { it.id.equals(itemId, ignoreCase = true) }
                if (item != null) return item
            }
        }
        return null
    }
}
