package core.luminaworld.module

import core.luminaworld.LuminaCore
import org.bukkit.event.HandlerList
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile

class ModuleManager(private val plugin: LuminaCore) {
    private val modulesList = ArrayList<LuminaModule>()

    val modules: List<LuminaModule>
        get() = modulesList

    fun getModule(name: String): LuminaModule? {
        return modulesList.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * ค้นหาและรันคลาสโมดูลทั้งหมดจากซอร์สโค้ดใน JAR
     */
    fun loadModules() {
        if (plugin.isStandalone) {
            val moduleClass = plugin.standaloneModuleClass
            val moduleName = plugin.standaloneModuleName
            if (moduleClass != null && moduleName != null) {
                try {
                    val clazz = Class.forName(moduleClass, true, plugin.javaClass.classLoader)
                    if (LuminaModule::class.java.isAssignableFrom(clazz) && 
                        !clazz.isInterface && 
                        !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {
                        
                        val constructor = clazz.getConstructor(LuminaCore::class.java)
                        val module = constructor.newInstance(plugin) as LuminaModule
                        modulesList.add(module)
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("[Lumina-$moduleName] Failed to load standalone module class: $moduleClass - ${e.message}")
                }
            }
        } else {
            val jarPath = try {
                val path = plugin.javaClass.protectionDomain.codeSource.location.path
                URLDecoder.decode(path, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                plugin.logger.severe("[LuminaCore] Could not decode plugin JAR path: ${e.message}")
                return
            }

            val jarFile = File(jarPath)
            if (!jarFile.exists()) {
                plugin.logger.warning("[LuminaCore] Could not find plugin JAR file at: $jarPath")
                return
            }

            try {
                JarFile(jarFile).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryName = entry.name
                        
                        // สแกนหา class ในแพ็คเกจ core.luminaworld.modules เท่านั้น
                        if (entryName.startsWith("core/luminaworld/modules/") && entryName.endsWith(".class") && !entryName.contains("$")) {
                            val className = entryName.replace('/', '.').substring(0, entryName.length - 6)
                            try {
                                val clazz = Class.forName(className, true, plugin.javaClass.classLoader)
                                if (LuminaModule::class.java.isAssignableFrom(clazz) && 
                                    !clazz.isInterface && 
                                    !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {
                                    
                                    val constructor = clazz.getConstructor(LuminaCore::class.java)
                                    val module = constructor.newInstance(plugin) as LuminaModule
                                    // ตรวจสอบ Duplicate Module Name ก่อน add
                                    if (modulesList.none { it.name.equals(module.name, ignoreCase = true) }) {
                                        modulesList.add(module)
                                    } else {
                                        plugin.logger.warning("[LuminaCore] Duplicate module name detected: '${module.name}' from class $className — skipped.")
                                    }
                                }
                            } catch (e: Exception) {
                                plugin.logger.severe("[LuminaCore] Failed to load module class: $className - ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe("[LuminaCore] Error scanning plugin JAR: ${e.message}")
            }
        }

        // โหลด config และสั่งเริ่มทำงานแต่ละโมดูลย่อย
        for (module in modulesList) {
            try {
                module.loadConfig()
                // Register module ตัวเองเป็น Listener เพื่อให้ base-class event handlers (เช่น onPlayerQuit) ทำงานได้
                plugin.server.pluginManager.registerEvents(module, plugin)
                if (module.isEnabled) {
                    module.onEnable()
                    if (plugin.isStandalone) {
                        plugin.logger.info("[Lumina-${plugin.standaloneModuleName}] Module ${module.name} enabled.")
                    } else {
                        plugin.logger.info("[LuminaCore] Module ${module.name} enabled.")
                    }
                } else {
                    if (plugin.isStandalone) {
                        plugin.logger.info("[Lumina-${plugin.standaloneModuleName}] Module ${module.name} is disabled.")
                    } else {
                        plugin.logger.info("[LuminaCore] Module ${module.name} is disabled.")
                    }
                }
            } catch (e: Exception) {
                if (plugin.isStandalone) {
                    plugin.logger.severe("[Lumina-${plugin.standaloneModuleName}] Failed to initialize module: ${module.name} | Error: ${e.message}")
                } else {
                    plugin.logger.severe("[LuminaCore] Failed to initialize module: ${module.name} | Error: ${e.message}")
                }
                e.printStackTrace()
            }
        }
    }

    /**
     * สั่งปิดและเคลียร์โมดูลทั้งหมดเพื่อเตรียมหยุดระบบ
     */
    fun disableModules() {
        for (module in modules) {
            if (module.isEnabled) {
                try {
                    module.onDisable()
                } catch (e: Exception) {
                    if (plugin.isStandalone) {
                        plugin.logger.severe("[Lumina-${plugin.standaloneModuleName}] Error disabling module ${module.name}: ${e.message}")
                    } else {
                        plugin.logger.severe("[LuminaCore] Error disabling module ${module.name}: ${e.message}")
                    }
                }
            }
            // Unregister module เองด้วย (สำหรับ base-class event handlers)
            HandlerList.unregisterAll(module)
        }
        modulesList.clear()
    }

    fun reloadModules() {
        disableModules()
        loadModules()
    }
}
