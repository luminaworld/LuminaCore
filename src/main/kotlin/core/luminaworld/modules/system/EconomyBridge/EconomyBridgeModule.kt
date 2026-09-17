package core.luminaworld.modules.system.EconomyBridge

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class EconomyBridgeModule(plugin: LuminaCore) : LuminaModule(plugin, "EconomyBridge") {

    override fun onEnable() {
        // เริ่มต้นการสร้างและผูกการบริการ
        EconomyBridgeService(plugin)
    }

    override fun onDisable() {
        // ทำลาย/ปิดการทำงานโมดูลการเงินกลาง
    }
}
