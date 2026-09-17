package core.luminaworld.modules.features.Shop

data class ShopSection(
    val id: String,
    val enabled: Boolean,
    val slot: Int,
    val title: String,
    val hidden: Boolean,
    val subSection: Boolean,
    val displayItem: Boolean,
    val economy: String?,
    val closeMenu: Boolean,
    val clickCommands: List<String>,
    val fillItemMaterial: String?,
    val fillItemName: String?,
    val fillItemLore: List<String>?,
    val itemLayout: List<String>?,
    val clickMappings: Map<String, String>?,
    val navBarMode: String?,
    val itemMaterial: String,
    val itemName: String?,
    val itemLore: List<String>?,
    val itemEnchantments: List<String>?,
    val itemGlow: Boolean,
    val itemSkullOwner: String?,
    val itemSkullTexture: String?,
    val itemArmorColor: String?,
    val itemStackSize: Int,
    val itemNbt: String?,
    
    // Dynamic Pricing settings สำหรับหมวดหมู่
    val dpEnabled: Boolean,
    val dpMaxStock: Int,
    val dpBuyPriceScaling: Double,
    val dpSellPriceScaling: Double
)

data class ShopItem(
    val id: String, // format: "sectionId.itemIndex"
    val sectionId: String,
    val material: String,
    val name: String?,
    val lore: List<String>?,
    val enchantments: List<String>?,
    val buyPrice: Double?,
    val sellPrice: Double?,
    val permission: String?,
    val potionTypes: List<String>?,
    val recipes: List<String>?,
    val runCommands: List<String>?,
    val nbtData: String?,
    val customModelData: Int? = null,
    val itemStackSize: Int = 1
)

data class ShopPage(
    val pageNumber: Int,
    val guiRows: Int,
    val title: String?,
    val items: MutableMap<Int, ShopItem> = HashMap() // slot to ShopItem
)
