package core.luminaworld.modules.AutoPlanter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player

class AutoPlanterModule(plugin: LuminaCore) : LuminaModule(plugin, "AutoPlanter") {

    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(AutoPlanterListener(plugin, this), plugin)
    }

    override fun onDisable() {}

    fun runPlanting(player: Player) {
        val item = player.inventory.itemInMainHand
        val seedMaterial = item.type
        val cropBlockMaterial = getCropBlockForSeed(seedMaterial)
        
        if (cropBlockMaterial == null) {
            val msg = config?.getString("messages.no-seeds", "%prefix% &cYou do not have enough seeds in your hand.") ?: ""
            sendNotification(player, msg)
            return
        }

        val radius = config?.getInt("settings.radius", 5) ?: 5
        val playerLoc = player.location
        val world = player.world
        val pX = playerLoc.blockX
        val pY = playerLoc.blockY
        val pZ = playerLoc.blockZ

        var plantCount = 0
        val itemsToReduce = ArrayList<Block>()

        // สแกนบล็อกรอบตัวผู้เล่นเพื่อหา Farmland
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                for (y in -2..2) {
                    val block = world.getBlockAt(pX + x, pY + y, pZ + z)
                    if (block.type == Material.FARMLAND) {
                        val above = block.getRelative(0, 1, 0)
                        if (above.type == Material.AIR) {
                            itemsToReduce.add(above)
                        }
                    }
                }
            }
        }

        if (itemsToReduce.isEmpty()) {
            val msg = config?.getString("messages.planted", "%prefix% &aSuccessfully planted %amount% crops!") ?: ""
            sendNotification(player, msg.replace("%amount%", "0"))
            return
        }

        // เริ่มลงมือปลูกพืช
        for (aboveBlock in itemsToReduce) {
            val currentItem = player.inventory.itemInMainHand
            if (currentItem.type != seedMaterial || currentItem.amount <= 0) break

            aboveBlock.type = cropBlockMaterial
            currentItem.amount = currentItem.amount - 1
            plantCount++
        }

        val msg = config?.getString("messages.planted", "%prefix% &aSuccessfully planted %amount% crops!") ?: ""
        sendNotification(player, msg.replace("%amount%", plantCount.toString()))
    }

    private fun getCropBlockForSeed(seed: Material): Material? {
        return when (seed) {
            Material.WHEAT_SEEDS -> Material.WHEAT
            Material.PUMPKIN_SEEDS -> Material.PUMPKIN_STEM
            Material.MELON_SEEDS -> Material.MELON_STEM
            Material.BEETROOT_SEEDS -> Material.BEETROOTS
            Material.CARROT -> Material.CARROTS
            Material.POTATO -> Material.POTATOES
            Material.SWEET_BERRIES -> Material.SWEET_BERRY_BUSH
            Material.TORCHFLOWER_SEEDS -> Material.TORCHFLOWER_CROP
            Material.PITCHER_POD -> Material.PITCHER_CROP
            Material.NETHER_WART -> Material.NETHER_WART
            else -> null
        }
    }
}
