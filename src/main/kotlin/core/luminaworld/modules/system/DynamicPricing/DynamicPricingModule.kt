package core.luminaworld.modules.system.DynamicPricing

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule

class DynamicPricingModule(plugin: LuminaCore) : LuminaModule(plugin, "DynamicPricing") {

    override fun onEnable() {
        DynamicPricingService(plugin)
    }

    override fun onDisable() {
        // ดำเนินการเมื่อปิดระบบ
    }
}
