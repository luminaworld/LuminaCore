package core.luminaworld.modules.activate.VeinMiner

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import java.util.ArrayDeque

class VeinMinerListener(
    private val plugin: LuminaCore,
    private val module: VeinMinerModule
) : Listener {

    private val processingBlocks = HashSet<Block>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val item = player.inventory.itemInMainHand

        if (!module.isEnabled) return
        if (!module.isModeEnabled(player)) return
        if (!item.type.name.endsWith("_PICKAXE")) return
        if (processingBlocks.contains(block)) return

        val material = block.type
        if (!isOre(material)) return

        // เช็คสิทธิ์การใช้งานของโมดูลย่อย
        if (!module.checkPermission(player)) return

        event.isCancelled = true
        runVeinMiner(player, block, material)
    }

    private fun runVeinMiner(player: Player, startBlock: Block, oreType: Material) {
        val maxBlocks = module.config?.getInt("settings.max-blocks", 64) ?: 64
        val itemInHand = player.inventory.itemInMainHand

        val oreQueue = ArrayDeque<Block>()
        val visitedOres = HashSet<Block>()

        oreQueue.add(startBlock)
        visitedOres.add(startBlock)

        // สแกนแบบ BFS 3D 26 ทิศทางรอบตัวแร่ที่ถูกขุด
        while (oreQueue.isNotEmpty() && visitedOres.size < maxBlocks) {
            val current = oreQueue.poll()

            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        val relative = current.getRelative(x, y, z)
                        
                        if (isSameOreFamily(relative.type, oreType) && !visitedOres.contains(relative)) {
                            visitedOres.add(relative)
                            if (visitedOres.size < maxBlocks) {
                                oreQueue.add(relative)
                            }
                        }
                    }
                }
            }
        }

        // ลงมือขุดบล็อกทั้งหมดทีละบล็อก
        for (oreBlock in visitedOres) {
            processingBlocks.add(oreBlock)
            oreBlock.breakNaturally(itemInHand)
            processingBlocks.remove(oreBlock)
        }
    }

    private fun isOre(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_ORE") || name == "ANCIENT_DEBRIS"
    }

    private fun isSameOreFamily(m1: Material, m2: Material): Boolean {
        if (m1 == m2) return true
        val n1 = m1.name.replace("DEEPSLATE_", "")
        val n2 = m2.name.replace("DEEPSLATE_", "")
        return n1 == n2
    }
}
