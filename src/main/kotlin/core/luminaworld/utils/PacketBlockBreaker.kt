package core.luminaworld.utils

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.Damageable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * ตัวเชื่อมต่อระบบ Jobs Reborn โดยตรงแบบเดียวกับ tree-feller (JobsRebornCompat2)
 * เรียก com.gamingmesh.jobs.actions.BlockActionInfo และ Jobs.action(...)
 */
object JobsRebornCompat {
    private var isInstalled = false
    private var initialized = false
    private var getJobsPlayerMethod: Method? = null
    private var playerManagerObj: Any? = null
    private var actionMethod: Method? = null
    private var blockActionInfoConstructor: Constructor<*>? = null
    private var breakActionType: Any? = null

    fun init() {
        if (initialized) return
        initialized = true
        try {
            val jobsPlugin = Bukkit.getPluginManager().getPlugin("Jobs")
            if (jobsPlugin == null || !jobsPlugin.isEnabled) {
                isInstalled = false
                return
            }

            val jobsClass = Class.forName("com.gamingmesh.jobs.Jobs")
            val actionTypeClass = Class.forName("com.gamingmesh.jobs.container.ActionType")
            val jobsPlayerClass = Class.forName("com.gamingmesh.jobs.container.JobsPlayer")

            // ActionType.BREAK
            breakActionType = java.lang.Enum.valueOf(actionTypeClass.asSubclass(Enum::class.java), "BREAK")

            // ค้นหาคลาส BlockActionInfo (ใน Jobs Reborn คือ com.gamingmesh.jobs.actions.BlockActionInfo)
            val actionInfoClass = try {
                Class.forName("com.gamingmesh.jobs.actions.BlockActionInfo")
            } catch (_: ClassNotFoundException) {
                try {
                    Class.forName("com.gamingmesh.jobs.container.ActionInfo")
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (actionInfoClass != null) {
                blockActionInfoConstructor = try {
                    actionInfoClass.getConstructor(Block::class.java, actionTypeClass)
                } catch (_: NoSuchMethodException) {
                    actionInfoClass.constructors.firstOrNull { it.parameterCount == 2 }
                }
            }

            // Jobs.getPlayerManager().getJobsPlayer(Player) หรือ Jobs.getJobsPlayer(Player)
            try {
                val getPlayerManagerMethod = jobsClass.getMethod("getPlayerManager")
                playerManagerObj = getPlayerManagerMethod.invoke(null)
                getJobsPlayerMethod = playerManagerObj?.javaClass?.getMethod("getJobsPlayer", Player::class.java)
            } catch (_: Throwable) {
                getJobsPlayerMethod = jobsClass.getMethod("getJobsPlayer", Player::class.java)
                playerManagerObj = null
            }

            // Jobs.action(JobsPlayer, ActionInfo) หรือ Jobs.action(JobsPlayer, ActionInfo, Block)
            for (m in jobsClass.methods) {
                if (m.name == "action" && m.parameterCount in 2..3) {
                    val params = m.parameterTypes
                    if (params[0] == jobsPlayerClass) {
                        actionMethod = m
                        break
                    }
                }
            }

            isInstalled = (getJobsPlayerMethod != null && actionMethod != null && blockActionInfoConstructor != null && breakActionType != null)
        } catch (_: Throwable) {
            isInstalled = false
        }
    }

    fun breakBlock(player: Player, block: Block) {
        if (!initialized) init()
        if (!isInstalled) return

        try {
            val jobsPlayer = if (playerManagerObj != null) {
                getJobsPlayerMethod?.invoke(playerManagerObj, player)
            } else {
                getJobsPlayerMethod?.invoke(null, player)
            } ?: return

            val actionInfo = blockActionInfoConstructor?.newInstance(block, breakActionType) ?: return

            if (actionMethod?.parameterCount == 2) {
                actionMethod?.invoke(null, jobsPlayer, actionInfo)
            } else if (actionMethod?.parameterCount == 3) {
                actionMethod?.invoke(null, jobsPlayer, actionInfo, block)
            }
        } catch (_: Throwable) {
            // ป้องกัน Error รบกวน console
        }
    }
}

/**
 * ตัวช่วยทำลายบล็อกระดับ Packet / Event Stream และอัปเดตสถิติผู้เล่น (Statistics)
 * พร้อมการเชื่อมต่อ Jobs Reborn และนับจำนวนบล็อกจริง 100%
 */
object PacketBlockBreaker {

    /**
     * ทำลายบล็อกเดี่ยวพร้อมมอบรางวัล Jobs Reborn และอัปเดตสถิติผู้เล่น (Minecraft Statistics)
     * @return true หากทำลายสำเร็จ, false หากเครื่องมือไม่พอหรือบล็อกไม่ถูกต้อง
     */
    fun breakSingleBlock(
        player: Player,
        block: Block,
        toolSuffix: String,
        processingBlocks: MutableSet<Block>
    ): Boolean {
        if (!player.isOnline) return false
        if (block.type.isAir) return false

        val item = player.inventory.itemInMainHand
        if (!item.type.name.endsWith(toolSuffix)) return false

        // ตรวจสอบความทนทานของเครื่องมือ ป้องกันเครื่องมือพังเสียหาย
        val meta = item.itemMeta
        if (meta is Damageable) {
            val maxDurability = item.type.maxDurability
            val currentDamage = meta.damage
            val remainingDurability = maxDurability - currentDamage
            if (remainingDurability <= 1) {
                return false
            }
        }

        val blockMat = block.type
        val toolMat = item.type

        // ส่งมอบรางวัล Jobs Reborn ผ่าน BlockActionInfo ก่อนบล็อกกลายเป็น AIR
        JobsRebornCompat.breakBlock(player, block)

        processingBlocks.add(block)
        val success = try {
            player.breakBlock(block)
        } catch (_: Throwable) {
            false
        } finally {
            processingBlocks.remove(block)
        }

        if (success) {
            // อัปเดตสถิติผู้เล่น (Minecraft Player Statistics: MINE_BLOCK และ USE_ITEM)
            try {
                if (blockMat != Material.AIR && !blockMat.isAir) {
                    player.incrementStatistic(Statistic.MINE_BLOCK, blockMat)
                }
                player.incrementStatistic(Statistic.USE_ITEM, toolMat)
            } catch (_: Throwable) {
                // ป้องกันกรณี Material บางชนิดไม่มีใน Statistic Registry
            }
        }

        return success
    }

    /**
     * สั่งทำลายบล็อกในรายการแบบกระจายคิวตามเวลา (Staggered Queue Dispatch)
     * ส่ง 1-2 บล็อกต่อ tick เพื่อให้ปลั๊กอินอาชีพตรวจจับและประมวลผลอย่างสมบูรณ์แบบ
     */
    fun breakBlocksStaggered(
        plugin: LuminaCore,
        player: Player,
        blocks: List<Block>,
        toolSuffix: String,
        processingBlocks: MutableSet<Block>,
        blocksPerTick: Int = 2,
        onComplete: (() -> Unit)? = null
    ) {
        if (blocks.isEmpty()) {
            onComplete?.invoke()
            return
        }

        val remainingBlocks = ArrayList(blocks)
        val index = AtomicInteger(0)
        val total = remainingBlocks.size

        var currentTick = 0L
        var countInCurrentTick = 0

        for (b in remainingBlocks) {
            val scheduledTick = currentTick
            val targetBlock = b

            if (scheduledTick == 0L) {
                val ok = breakSingleBlock(player, targetBlock, toolSuffix, processingBlocks)
                val currentCount = index.incrementAndGet()
                if (!ok || currentCount >= total) {
                    if (currentCount >= total) onComplete?.invoke()
                }
            } else {
                player.scheduler.runDelayed(plugin, { _ ->
                    if (!player.isOnline) return@runDelayed
                    val ok = breakSingleBlock(player, targetBlock, toolSuffix, processingBlocks)
                    val currentCount = index.incrementAndGet()
                    if (!ok || currentCount >= total) {
                        if (currentCount >= total) {
                            onComplete?.invoke()
                        }
                    }
                }, null, scheduledTick)
            }

            countInCurrentTick++
            if (countInCurrentTick >= blocksPerTick) {
                countInCurrentTick = 0
                currentTick++
            }
        }
    }
}
