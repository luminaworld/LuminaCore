package core.luminaworld.modules.activate.VeinMiner

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import java.util.ArrayDeque
import java.util.Collections

class VeinMinerListener(
    private val plugin: LuminaCore,
    private val module: VeinMinerModule
) : Listener {

    private val processingBlocks = Collections.synchronizedSet(HashSet<Block>())

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val item = player.inventory.itemInMainHand

        if (!module.isEnabled) return
        if (!module.isModeEnabled(player)) return
        
        val requireSneak = module.requireSneak
        if (requireSneak && !player.isSneaking) return

        if (!item.type.name.endsWith("_PICKAXE")) return
        if (processingBlocks.contains(block)) return

        // ตรวจสอบเครื่องมือขุดแร่ที่อนุญาต (ถ้ามีกำหนด)
        val allowedPickaxes = module.allowedPickaxes
        if (allowedPickaxes.isNotEmpty() && !allowedPickaxes.contains("*")) {
            if (!allowedPickaxes.any { it.equals(item.type.name, ignoreCase = true) }) {
                return
            }
        }

        val material = block.type
        if (!isOre(material)) return

        // ตรวจสอบชนิดแร่ที่อนุญาต (ถ้ามีกำหนด)
        val allowedBlocks = module.allowedBlocks
        if (allowedBlocks.isNotEmpty() && !allowedBlocks.contains("*")) {
            if (!allowedBlocks.any { it.equals(material.name, ignoreCase = true) }) {
                return
            }
        }

        // เช็คสิทธิ์การใช้งานของโมดูลย่อย
        if (!module.checkPermission(player)) return

        // เริ่มขั้นตอนสแกนและขุดแร่ทั้งยวง (ไม่ยกเลิก event ของ startBlock เพื่อให้ระบบหลักและปลั๊กอินอื่นประมวลผลบล็อกแรกตามปกติ)
        runVeinMiner(player, block, material)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockDropItem(event: BlockDropItemEvent) {
        val block = event.block
        if (processingBlocks.contains(block) && event.blockState.type == Material.ANCIENT_DEBRIS) {
            val debrisItems = event.items.filter { it.itemStack.type == Material.ANCIENT_DEBRIS }
            if (debrisItems.isNotEmpty()) {
                event.items.removeAll(debrisItems)
                val firstItem = debrisItems[0]
                val stack = firstItem.itemStack
                stack.amount = 1
                firstItem.itemStack = stack
                event.items.add(firstItem)
            }
        }
    }

    private fun runVeinMiner(player: Player, startBlock: Block, oreType: Material) {
        val maxBlocks = module.maxBlocks
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

        // ขุดบล็อกแร่ที่เหลือทั้งหมดผ่าน PacketBlockBreaker แบบกระจายคิวตามลำดับ Tick
        val remainingOres = visitedOres.filter { it != startBlock }
        core.luminaworld.utils.PacketBlockBreaker.breakBlocksStaggered(
            plugin,
            player,
            remainingOres,
            "_PICKAXE",
            processingBlocks,
            blocksPerTick = 2
        )
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
