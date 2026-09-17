package core.luminaworld.gui

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.Component

class LuminaGUIHolder(val gui: LuminaGUI) : InventoryHolder {
    private var inv: Inventory? = null
    fun setInventory(inventory: Inventory) {
        this.inv = inventory
    }
    override fun getInventory(): Inventory {
        return inv ?: Bukkit.createInventory(this, 54)
    }
}

class LuminaGUI(
    private val plugin: LuminaCore,
    val title: Component,
    val size: Int,
    val cancelClicks: Boolean = true
) {
    private val buttons = HashMap<Int, GUIButton>()
    var onClose: ((Player, Inventory) -> Unit)? = null

    data class GUIButton(val item: ItemStack, val onClick: (Player, ClickType) -> Unit)

    fun setButton(slot: Int, item: ItemStack, onClick: (Player, ClickType) -> Unit) {
        if (slot in 0 until size) {
            buttons[slot] = GUIButton(item, onClick)
        }
    }

    fun fill(item: ItemStack) {
        for (i in 0 until size) {
            if (!buttons.containsKey(i)) {
                setButton(i, item) { _, _ -> }
            }
        }
    }

    fun getButton(slot: Int): GUIButton? = buttons[slot]
    fun hasButton(slot: Int): Boolean = buttons.containsKey(slot)

    fun open(player: Player) {
        val holder = LuminaGUIHolder(this)
        val inv = Bukkit.createInventory(holder, size, title)
        holder.setInventory(inv)

        for ((slot, button) in buttons) {
            inv.setItem(slot, button.item)
        }

        player.scheduler.execute(plugin, {
            player.openInventory(inv)
        }, null, 0)
    }
}

/**
 * Listener ส่วนกลางตัวเดียวเพื่อดักฟัง GUI ทั้งหมด ป้องกัน Memory Leak และ Item Theft / Duplication Exploit
 */
class LuminaGUIListener(private val plugin: LuminaCore) : Listener {
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? LuminaGUIHolder ?: return

        // 1. หากตั้งค่า cancelClicks = true (เช่น หน้า Shop ปกติ, เมนูเลือกจำนวน, เมนูหมวดหมู่) ให้ยกเลิกการคลิกทั้งหมด
        if (holder.gui.cancelClicks) {
            event.isCancelled = true
        }

        val player = event.whoClicked as? Player ?: return
        val rawSlot = event.rawSlot
        val topSize = event.inventory.size

        // 2. ตรวจสอบการคลิกในเมนูด้านบน (Top Inventory)
        if (rawSlot in 0 until topSize) {
            val button = holder.gui.getButton(rawSlot)
            if (button != null) {
                // ป้องกันการหยิบปุ่มตกแต่งหรือปุ่มระบบออกมา แม้ cancelClicks จะเป็น false (เช่น เมนู SellGUI)
                event.isCancelled = true
                button.onClick(player, event.click)
            }
        } else if (!holder.gui.cancelClicks) {
            // 3. ตรวจสอบการคลิกจากกระเป๋าผู้เล่น (Bottom Inventory) ในขณะที่เปิดเมนูที่วางของได้ (เช่น SellGUI)
            if (event.isShiftClick) {
                // ตรวจสอบว่ามีช่องว่างในส่วนที่อนุญาตให้วาง (ช่องที่ไม่มีปุ่ม) หรือไม่ เพื่อป้องกันของล้นไปทับช่องปุ่ม
                val topInv = event.inventory
                var hasFreeAllowedSlot = false
                val clickedItem = event.currentItem

                for (i in 0 until topSize) {
                    if (!holder.gui.hasButton(i)) {
                        val currentItem = topInv.getItem(i)
                        if (currentItem == null || currentItem.type.isAir) {
                            hasFreeAllowedSlot = true
                            break
                        } else if (clickedItem != null && currentItem.isSimilar(clickedItem) && currentItem.amount < currentItem.maxStackSize) {
                            hasFreeAllowedSlot = true
                            break
                        }
                    }
                }

                if (!hasFreeAllowedSlot) {
                    event.isCancelled = true
                }
            }

            // ป้องกันการกดดับเบิ้ลคลิก (Collect to cursor) เพื่อดึงไอเทมปุ่มจากเมนูด้านบนลงมา
            if (event.action == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder as? LuminaGUIHolder ?: return
        val topSize = event.inventory.size

        if (holder.gui.cancelClicks) {
            // ป้องกันการลากไอเทมทั้งหมดในเมนูที่ไม่อนุญาตให้แก้ไข
            event.isCancelled = true
            return
        }

        // สำหรับเมนูที่อนุญาตให้วางของได้ (เช่น SellGUI) ยกเลิกหากมีการลากผ่านช่องที่มีปุ่ม
        for (slot in event.rawSlots) {
            if (slot < topSize && holder.gui.hasButton(slot)) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? LuminaGUIHolder ?: return
        val player = event.player as? Player ?: return
        holder.gui.onClose?.invoke(player, event.inventory)
    }
}
