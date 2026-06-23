package core.luminaworld.modules.activate.TreeCapitator

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import java.util.ArrayDeque

class TreeCapitatorListener(
    private val plugin: LuminaCore,
    private val module: TreeCapitatorModule
) : Listener {

    // เก็บรายการบล็อกที่ระบบกำลังทำลายเพื่อหลีกเลี่ยง Loop ซ้อนทับ
    private val processingBlocks = HashSet<Block>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val item = player.inventory.itemInMainHand

        // ตรวจสอบสถานะการทำงานและโหมด
        if (!module.isEnabled) return
        if (!module.isModeEnabled(player)) return
        if (!item.type.name.endsWith("_AXE")) return
        if (processingBlocks.contains(block)) return

        val material = block.type
        if (!isLog(material)) return

        // ตรวจสอบว่าผู้เล่นมีสิทธิ์หรือไม่
        if (!module.checkPermission(player)) return

        // เริ่มขั้นตอนสแกนและตัดไม้หมดต้น
        event.isCancelled = true // ยกเลิก event ดั้งเดิมเพื่อจัดการขุดเองทั้งหมด
        runTreeCapitator(player, block, material)
    }

    private fun runTreeCapitator(player: Player, startBlock: Block, logType: Material) {
        val maxBlocks = module.config?.getInt("settings.max-blocks", 128) ?: 128
        val breakLeaves = module.config?.getBoolean("settings.break-leaves", true) ?: true
        val replantSapling = module.config?.getBoolean("settings.replant-sapling", true) ?: true
        val itemInHand = player.inventory.itemInMainHand

        // เช็คว่าจุดเริ่มต้นเป็นท่อนล่างสุดเพื่อเตรียมปลูกต้นอ่อนคืน
        val isBottomLog = isSoil(startBlock.getRelative(0, -1, 0).type)

        val logQueue = ArrayDeque<Block>()
        val visitedLogs = HashSet<Block>()
        val leavesToBreak = HashSet<Block>()

        logQueue.add(startBlock)
        visitedLogs.add(startBlock)

        // ค้นหาบล็อกไม้ (BFS)
        while (logQueue.isNotEmpty() && visitedLogs.size < maxBlocks) {
            val current = logQueue.poll()

            // สแกนรอบข้าง 3x3x3
            for (x in -1..1) {
                for (y in 0..1) { // เน้นสแกนขึ้นด้านบนและระดับเดียวกัน
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        val relative = current.getRelative(x, y, z)
                        
                        if (relative.type == logType && !visitedLogs.contains(relative)) {
                            visitedLogs.add(relative)
                            logQueue.add(relative)
                        } else if (breakLeaves && isLeaves(relative.type)) {
                            leavesToBreak.add(relative)
                        }
                    }
                }
            }
        }

        // ทำลายบล็อกไม้ทั้งหมด
        for (logBlock in visitedLogs) {
            processingBlocks.add(logBlock)
            logBlock.breakNaturally(itemInHand)
            processingBlocks.remove(logBlock)
        }

        // ทำลายบล็อกใบไม้ที่ค้นพบรอบๆ
        if (breakLeaves && leavesToBreak.isNotEmpty()) {
            for (leafBlock in leavesToBreak) {
                if (isLeaves(leafBlock.type)) {
                    processingBlocks.add(leafBlock)
                    leafBlock.breakNaturally()
                    processingBlocks.remove(leafBlock)
                }
            }
        }

        // ปลูกต้นอ่อนกลับคืนจุดเดิม
        if (replantSapling && isBottomLog) {
            val saplingType = getSaplingForLog(logType)
            if (saplingType != null) {
                // รอ 1 tick เพื่อให้บล็อกไม้เดิมสลายตัวเรียบร้อยก่อน
                plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                    startBlock.type = saplingType
                }, 1L)
            }
        }
    }

    private fun isLog(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || 
               name.contains("STEM") || name.contains("HYPHAE") || 
               name == "BAMBOO_BLOCK" || name == "CHERRY_LOG" || name == "MANGROVE_LOG"
    }

    private fun isLeaves(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_LEAVES") || name == "MANGROVE_LEAVES" || name == "AZALEA_LEAVES" || name == "FLOWERING_AZALEA_LEAVES"
    }

    private fun isSoil(material: Material): Boolean {
        val name = material.name
        return name == "GRASS_BLOCK" || name == "DIRT" || name == "COARSE_DIRT" || 
               name == "PODZOL" || name == "ROOTED_DIRT" || name == "MOSS_BLOCK" || 
               name == "FARMLAND" || name == "MYCELIUM"
    }

    private fun getSaplingForLog(logMaterial: Material): Material? {
        val name = logMaterial.name
        return when {
            name.contains("DARK_OAK") -> Material.DARK_OAK_SAPLING
            name.contains("OAK") -> Material.OAK_SAPLING
            name.contains("SPRUCE") -> Material.SPRUCE_SAPLING
            name.contains("BIRCH") -> Material.BIRCH_SAPLING
            name.contains("JUNGLE") -> Material.JUNGLE_SAPLING
            name.contains("ACACIA") -> Material.ACACIA_SAPLING
            name.contains("MANGROVE") -> Material.MANGROVE_PROPAGULE
            name.contains("CHERRY") -> Material.CHERRY_SAPLING
            name.contains("BAMBOO") -> Material.BAMBOO_SAPLING
            name.contains("CRIMSON") -> Material.CRIMSON_NYLIUM // Crimson stem -> Crimson Nylium/Fungus
            name.contains("WARPED") -> Material.WARPED_NYLIUM
            else -> null
        }
    }
}
