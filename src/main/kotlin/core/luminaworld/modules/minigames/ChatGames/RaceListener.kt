package core.luminaworld.modules.minigames.ChatGames

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerItemConsumeEvent

class RaceListener(private val module: ChatGamesModule) : Listener {

    /**
     * ตรวจสอบว่าผู้เล่นสามารถเข้าร่วมและนับความคืบหน้าของ Race ได้หรือไม่
     */
    private fun canParticipate(player: Player): Boolean {
        if (!module.isEnabled) return false
        val game = module.gameManager.currentGame ?: return false
        if (!game.isRace) return false

        // ตรวจสอบโลกที่ปิดใช้งาน
        val disabledWorlds = module.chatConfig.config.getStringList("disabled-worlds")
        if (disabledWorlds.contains(player.world.name)) return false

        // ตรวจสอบโหมด Creative
        val creativeEnabled = module.chatConfig.chatRaces.getBoolean("creative-enabled", true)
        if (player.gameMode == GameMode.CREATIVE && !creativeEnabled) return false

        return true
    }

    /**
     * อัปเดตความคืบหน้าของภารกิจ
     */
    private fun updateProgress(player: Player, game: ActiveGame, increment: Int) {
        val uuid = player.uniqueId
        val currentProgress = game.raceProgress.getOrDefault(uuid, 0)
        val newProgress = currentProgress + increment
        game.raceProgress[uuid] = newProgress

        // หากผ่านเป้าหมายที่กำหนดในกิจกรรมสำเร็จ
        if (newProgress >= game.raceTargetAmount) {
            val timeTaken = (System.currentTimeMillis() - game.startTime) / 1000.0
            module.gameManager.handleWinner(player, timeTaken)
        }
    }

    /**
     * 1. hunt: ล่าเอนทิตี
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        if (!canParticipate(killer)) return
        val game = module.gameManager.currentGame ?: return
        if (game.type != "hunt") return

        // ตรวจสอบว่าตรงกับประเภทของมอนสเตอร์เป้าหมายหรือไม่
        val entityTypeName = event.entity.type.name
        if (entityTypeName.equals(game.raceTargetValue, ignoreCase = true)) {
            
            // เช็คการเกิดจากกรงสปอว์น (ถ้าปิด spawner mobs ใน config)
            val countSpawner = module.chatConfig.chatRaces.getBoolean("hunt.count-spawner-mobs", true)
            if (!countSpawner) {
                // ตรวจเช็ค metadata ของมอนสเตอร์
                if (event.entity.hasMetadata("spawn-reason")) {
                    val meta = event.entity.getMetadata("spawn-reason")
                    for (v in meta) {
                        if (v.asString().contains("SPAWNER", ignoreCase = true)) {
                            return
                        }
                    }
                }
            }

            updateProgress(killer, game, 1)
        }
    }

    /**
     * 2. mine: ขุดบล็อก
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (!canParticipate(player)) return
        val game = module.gameManager.currentGame ?: return
        if (game.type != "mine") return

        val blockTypeName = event.block.type.name
        if (blockTypeName.equals(game.raceTargetValue, ignoreCase = true)) {
            updateProgress(player, game, 1)
        }
    }

    /**
     * 3. place: วางบล็อก
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (!canParticipate(player)) return
        val game = module.gameManager.currentGame ?: return
        if (game.type != "place") return

        val blockTypeName = event.block.type.name
        if (blockTypeName.equals(game.raceTargetValue, ignoreCase = true)) {
            updateProgress(player, game, 1)
        }
    }

    /**
     * 4. fish: ตกปลาสำเร็จ
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerFish(event: PlayerFishEvent) {
        val player = event.player
        if (!canParticipate(player)) return
        val game = module.gameManager.currentGame ?: return
        if (game.type != "fish") return

        // ตรวจจับเมื่อตกเบ็ดและได้ปลาขึ้นมาจริง
        if (event.state == PlayerFishEvent.State.CAUGHT_FISH) {
            updateProgress(player, game, 1)
        }
    }

    /**
     * 5. eat: กินอาหารสำเร็จ
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerEat(event: PlayerItemConsumeEvent) {
        val player = event.player
        if (!canParticipate(player)) return
        val game = module.gameManager.currentGame ?: return
        if (game.type != "eat") return

        val itemTypeName = event.item.type.name
        if (itemTypeName.equals(game.raceTargetValue, ignoreCase = true)) {
            updateProgress(player, game, 1)
        }
    }

    /**
     * 6. craft: คราฟต์ไอเทม
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraftItem(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!canParticipate(player)) return
        val game = module.gameManager.currentGame ?: return
        if (game.type != "craft") return

        val recipeResult = event.recipe?.result ?: return
        val resultTypeName = recipeResult.type.name

        if (resultTypeName.equals(game.raceTargetValue, ignoreCase = true)) {
            val amount = recipeResult.amount
            var craftedCount = amount

            if (event.isShiftClick) {
                // จำลองปริมาณการคราฟต์ด้วย Shift-Click แบบทนทาน
                var maxCrafts = Int.MAX_VALUE
                for (item in event.inventory.matrix) {
                    if (item != null && item.type != Material.AIR) {
                        maxCrafts = Math.min(maxCrafts, item.amount)
                    }
                }
                if (maxCrafts != Int.MAX_VALUE && maxCrafts > 0) {
                    craftedCount = maxCrafts * amount
                }
            }

            updateProgress(player, game, craftedCount)
        }
    }

    /**
     * 7. furnace: เผาและเก็บไอเทมออกจากเตาเผา
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!canParticipate(player)) return
        val game = module.gameManager.currentGame ?: return
        if (game.type != "furnace") return

        val inv = event.inventory
        val type = inv.type
        if (type == InventoryType.FURNACE || type == InventoryType.BLAST_FURNACE || type == InventoryType.SMOKER) {
            // ดักฟังการคลิกหยิบไอเทมผลผลิตใน Slot 2 (ช่องผลลัพธ์)
            if (event.slot == 2) {
                val currentItem = event.currentItem ?: return
                val itemTypeName = currentItem.type.name
                if (itemTypeName.equals(game.raceTargetValue, ignoreCase = true)) {
                    val amount = currentItem.amount
                    updateProgress(player, game, amount)
                }
            }
        }
    }
}
