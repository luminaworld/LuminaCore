package core.luminaworld.modules.activate.TreeCapitator

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.ArrayDeque
import java.util.Collections

class TreeCapitatorListener(
    private val plugin: LuminaCore,
    private val module: TreeCapitatorModule
) : Listener {

    // เก็บรายการบล็อกที่ระบบกำลังทำลายเพื่อหลีกเลี่ยง Loop ซ้อนทับ (Thread-safe)
    private val processingBlocks = Collections.synchronizedSet(HashSet<Block>())

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

        // ตรวจสอบระดับขวานที่อนุญาต (ถ้ามีกำหนด)
        val allowedAxes = module.allowedAxes
        if (allowedAxes.isNotEmpty() && !allowedAxes.contains("*")) {
            if (!allowedAxes.any { it.equals(item.type.name, ignoreCase = true) }) {
                return
            }
        }

        val material = block.type
        if (!isLog(material)) return

        // ตรวจสอบประเภทบล็อกไม้ที่อนุญาต (ถ้ามีกำหนด)
        val allowedBlocks = module.allowedBlocks
        if (allowedBlocks.isNotEmpty() && !allowedBlocks.contains("*")) {
            if (!allowedBlocks.any { it.equals(material.name, ignoreCase = true) }) {
                return
            }
        }

        // ตรวจสอบว่าผู้เล่นมีสิทธิ์หรือไม่
        if (!module.checkPermission(player)) return

        // เริ่มขั้นตอนสแกนและตัดไม้หมดต้น
        event.isCancelled = true // ยกเลิก event ดั้งเดิมเพื่อจัดการขุดเองทั้งหมด
        runTreeCapitator(player, block, material)
    }

    private fun runTreeCapitator(player: Player, startBlock: Block, logType: Material) {
        val maxBlocks = module.maxBlocks
        val breakLeaves = module.breakLeaves
        val replantSapling = module.replantSapling
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

        // ตรวจสอบว่ามีต้นไม้อื่นอยู่ใกล้เคียงหรือไม่
        val anotherTreeNearby = isAnotherTreeNearby(visitedLogs)

        // ค้นหาบล็อกใบไม้ทั้งหมดที่เชื่อมต่อกัน (BFS) เฉพาะเมื่อไม่มีต้นไม้อื่นอยู่ใกล้เคียง
        if (breakLeaves && !anotherTreeNearby && leavesToBreak.isNotEmpty()) {
            val leafQueue = ArrayDeque<Pair<Block, Int>>()
            for (leaf in leavesToBreak) {
                leafQueue.add(Pair(leaf, 1))
            }
            
            val maxLeaves = maxBlocks * 8
            val maxDepth = 6 // ระยะห่างสูงสุดจากท่อนไม้เพื่อความปลอดภัย
            
            val visitedLeaves = HashSet<Block>(leavesToBreak)
            
            while (leafQueue.isNotEmpty() && visitedLeaves.size < maxLeaves) {
                val (current, depth) = leafQueue.poll()
                if (depth >= maxDepth) continue
                
                for (x in -1..1) {
                    for (y in -1..1) {
                        for (z in -1..1) {
                            if (x == 0 && y == 0 && z == 0) continue
                            val relative = current.getRelative(x, y, z)
                            
                            if (isLeaves(relative.type) && !visitedLeaves.contains(relative)) {
                                visitedLeaves.add(relative)
                                leafQueue.add(Pair(relative, depth + 1))
                            }
                        }
                    }
                }
            }
            leavesToBreak.clear()
            leavesToBreak.addAll(visitedLeaves)
        } else if (anotherTreeNearby) {
            // หากมีต้นไม้อื่นอยู่ใกล้เคียง จะไม่ทำลายใบไม้เลยตามความต้องการของผู้ใช้
            leavesToBreak.clear()
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
                plugin.server.regionScheduler.runDelayed(plugin, startBlock.location, { _ ->
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
            name.contains("PALE_OAK") -> Material.PALE_OAK_SAPLING
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

    private fun isAnotherTreeNearby(visitedLogs: Set<Block>): Boolean {
        val searchRadius = 6
        val checkedCoordinates = HashSet<Long>()
        
        for (log in visitedLogs) {
            val world = log.world
            val cx = log.x
            val cy = log.y
            val cz = log.z
            
            for (x in -searchRadius..searchRadius) {
                for (z in -searchRadius..searchRadius) {
                    for (y in -3..3) {
                        if (x == 0 && y == 0 && z == 0) continue
                        val checkX = cx + x
                        val checkY = cy + y
                        val checkZ = cz + z
                        
                        val key = (checkX.toLong() and 0x3FFFFFFL shl 38) or 
                                  (checkZ.toLong() and 0x3FFFFFFL shl 12) or 
                                  (checkY.toLong() and 0xFFFL)
                                  
                        if (!checkedCoordinates.add(key)) continue
                        
                        val checkBlock = world.getBlockAt(checkX, checkY, checkZ)
                        if (isLog(checkBlock.type) && !visitedLogs.contains(checkBlock)) {
                            // ตรวจสอบว่าบล็อกไม้นี้อยู่ห่างจากท่อนไม้ทั้งหมดใน visitedLogs ทางแนวราบอย่างน้อย 2 บล็อก
                            // หากอยู่ใกล้กว่านั้น (ระยะห่างทางแนวราบ x และ z น้อยกว่า 2 บล็อก) ถือว่าเป็นท่อนไม้ส่วนล่าง/โคนของต้นเดียวกัน
                            val isPartofSameTree = visitedLogs.any { vLog ->
                                Math.abs(checkX - vLog.x) < 2 && Math.abs(checkZ - vLog.z) < 2
                            }
                            if (!isPartofSameTree) {
                                return true
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        module.clearPlayer(event.player)
    }
}
