const Bukkit = org.bukkit.Bukkit;
const YamlConfiguration = importClass("org.bukkit.configuration.file.YamlConfiguration");
const File = importClass("java.io.File");
const ArrayList = importClass("java.util.ArrayList");

/**
 * Configuration
 */
const FPP_POOL_PATH = "plugins/FakePlayerPlugin/bot-names.yml";
const MANAGED_NAMES_PATH = "plugins/OpenJS/saveFiles/fpp-name-list.yml";
const ACTIVE_BOTS_STATE_PATH = "plugins/OpenJS/saveFiles/fpp-active-bots.yml";

const SPAWN_CMD = "fp spawn 1 spawn --name {name} -1244 70 1668";
const DESPAWN_CMD = "fp despawn {name}";

// State
let activeBots = [];
let pendingSpawns = [];
let pendingDespawns = [];
let periodicTask;
let freezeAdd = false;
let freezeRemove = false;
let maxBotCap = -1;

// Cache for yaml names to avoid heavy disk operations during player event bursts
let cachedFppPool = [];
let cachedManagedNames = [];
let lastFppPoolLoad = 0;
let lastManagedNamesLoad = 0;

/**
 * Cache-supported Helper to get names from a specific YAML file
 */
function getNamesFromYaml(path) {
    const now = java.lang.System.currentTimeMillis();
    if (path === FPP_POOL_PATH && (now - lastFppPoolLoad < 5000)) {
        return cachedFppPool;
    }
    if (path === MANAGED_NAMES_PATH && (now - lastManagedNamesLoad < 5000)) {
        return cachedManagedNames;
    }

    try {
        const file = new File(path);
        if (!file.exists()) {
            log.warn("File not found: " + file.getAbsolutePath());
            return [];
        }
        const yaml = YamlConfiguration.loadConfiguration(file);

        let result = [];
        // 1. Try old format (name: [a, b, c])
        let names = yaml.getStringList("name");
        if (names != null && !names.isEmpty()) {
            for (let i = 0; i < names.size(); i++) {
                result.push(names.get(i));
            }
        } else {
            // 2. Try new format (bots: { name: rank })
            const section = yaml.getConfigurationSection("bots");
            if (section != null) {
                const keys = section.getKeys(false).iterator();
                while (keys.hasNext()) {
                    result.push(keys.next());
                }
            }
        }

        if (path === FPP_POOL_PATH) {
            cachedFppPool = result;
            lastFppPoolLoad = now;
        } else if (path === MANAGED_NAMES_PATH) {
            cachedManagedNames = result;
            lastManagedNamesLoad = now;
        }
        return result;
    } catch (e) {
        log.error("Error reading " + path + ": " + e);
        return [];
    }
}

/**
 * Persistence Helpers
 */
function getActiveBotsFromState() {
    try {
        const file = new File(ACTIVE_BOTS_STATE_PATH);
        if (!file.exists()) return [];
        const yaml = YamlConfiguration.loadConfiguration(file);
        const list = yaml.getStringList("active");
        const result = [];
        if (list == null) return [];
        for (let i = 0; i < list.size(); i++) {
            result.push(list.get(i));
        }
        return result;
    } catch (e) {
        log.error("Error reading active bots state: " + e);
        return [];
    }
}

function saveActiveBotsState(list) {
    try {
        const file = new File(ACTIVE_BOTS_STATE_PATH);
        const yaml = new YamlConfiguration();

        // Convert JS Array to Java ArrayList for Bukkit YAML
        const javaList = new ArrayList();
        for (let i = 0; i < list.length; i++) {
            javaList.add(list[i]);
        }

        yaml.set("active", javaList);
        yaml.save(file);
    } catch (e) {
        log.error("Error saving active bots state: " + e);
    }
}

function addActiveBot(name) {
    const list = getActiveBotsFromState();
    if (list.indexOf(name) === -1) {
        list.push(name);
        saveActiveBotsState(list);
    }
}

function removeActiveBot(name) {
    let list = getActiveBotsFromState();
    list = list.filter(n => n !== name);
    saveActiveBotsState(list);
}

function syncActiveBots() {
    activeBots = getActiveBotsFromState();
}

/**
 * Get rank for a specific bot from MANAGED_NAMES_PATH
 */
function getBotRank(name) {
    try {
        const file = new File(MANAGED_NAMES_PATH);
        if (!file.exists()) return null;
        const yaml = YamlConfiguration.loadConfiguration(file);
        const section = yaml.getConfigurationSection("bots");
        if (section == null) return null;
        return section.getString(name);
    } catch (e) {
        log.error("Error getting rank for " + name + ": " + e);
        return null;
    }
}

/**
 * Check if a player is a bot
 */
function isBot(player) {
    if (player == null) return false;
    const name = player.getName();

    // 1. Check against the entire FPP Pool
    const fppPool = getNamesFromYaml(FPP_POOL_PATH);
    if (fppPool.indexOf(name) !== -1) return true;

    // 2. Check against our managed list
    const managedNames = getNamesFromYaml(MANAGED_NAMES_PATH);
    if (managedNames.indexOf(name) !== -1) return true;

    // 3. Fallback: Structural checks for manual bots with names NOT in any list
    const addr = player.getAddress();
    if (addr == null) return true;
    const host = addr.getHostString();
    if (host === "127.0.0.1" || host === "localhost") return true;

    if (player.hasMetadata("NPC") || player.hasMetadata("fakeplayer")) return true;

    const className = player.getClass().getSimpleName();
    if (className.indexOf("FakePlayer") !== -1 || className.indexOf("NPC") !== -1) return true;

    return false;
}

/**
 * Helper for random range
 */
function getRandomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

/**
 * Helper functions to get online status
 */
function getOnlineRealPlayersCount() {
    const online = Bukkit.getOnlinePlayers();
    let count = 0;
    for (let i = 0; i < online.size(); i++) {
        if (!isBot(online.get(i))) {
            count++;
        }
    }
    return count;
}

function getOnlineManagedBots() {
    const online = Bukkit.getOnlinePlayers();
    const result = [];
    const managedNames = getNamesFromYaml(MANAGED_NAMES_PATH);
    for (let i = 0; i < online.size(); i++) {
        const p = online.get(i);
        const name = p.getName();
        if (managedNames.indexOf(name) !== -1) {
            result.push(name);
        }
    }
    return result;
}

function getTargetBotCount(realPlayerCount) {
    const currentHour = new Date().getHours();
    if (currentHour >= 21 || currentHour < 12) {
        return Math.min(10, realPlayerCount);
    }
    return realPlayerCount;
}

/**
 * Reconciliation Engine
 * Automatically aligns online bot count with real player count safely & asynchronously.
 * Perfectly resilient to rapid player joins/leaves or unexpected state issues.
 */
function reconcile(reason) {
    if (freezeAdd && freezeRemove) {
        log.info("[FP-Manager] Reconciliation fully paused (Spawning & Despawning frozen). Skipping check. (Reason: " + reason + ")");
        return;
    }
    try {
        const onlineRealCount = getOnlineRealPlayersCount();
        let target = getTargetBotCount(onlineRealCount);
        if (maxBotCap > -1 && target > maxBotCap) {
            target = maxBotCap;
        }
        const onlineBots = getOnlineManagedBots();

        // Keep activeBots state fully synchronized with actual online managed bots
        saveActiveBotsState(onlineBots);
        activeBots = onlineBots;

        const virtualCount = onlineBots.length + pendingSpawns.length - pendingDespawns.length;

        log.info("[FP-Manager] Reconciling due to: " + reason);
        log.info("[FP-Manager] Real players: " + onlineRealCount + " | Target bots: " + target + " | Online bots: " + onlineBots.length + " | Pending spawns: " + pendingSpawns.length + " | Pending despawns: " + pendingDespawns.length + " | Virtual bots: " + virtualCount);

        const diff = target - virtualCount;

        if (diff > 0) {
            if (freezeAdd) {
                log.info("[FP-Manager] Spawning is frozen. Skipping bot spawns.");
                return;
            }
            const managedNames = getNamesFromYaml(MANAGED_NAMES_PATH);
            const available = managedNames.filter(n =>
                onlineBots.indexOf(n) === -1 &&
                pendingSpawns.indexOf(n) === -1
            );

            const toSpawnCount = Math.min(diff, available.length);
            if (toSpawnCount > 0) {
                for (let i = 0; i < toSpawnCount; i++) {
                    const name = available[i];
                    pendingSpawns.push(name);
                    log.info("[FP-Manager] Selected bot to spawn: " + name);

                    const staggerDelay = i * 1.5;
                    task.delay(staggerDelay, () => {
                        task.main(() => {
                            log.info("[FP-Manager] Executing spawn command for: " + name);
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), SPAWN_CMD.replace("{name}", name));
                            addActiveBot(name);

                            // Fallback timeout in case bot fails to join
                            task.delay(5, () => {
                                pendingSpawns = pendingSpawns.filter(n => n !== name);
                            });
                        });
                    });
                }
            } else {
                log.warn("[FP-Manager] No available bot names to spawn!");
            }
        } else if (diff < 0) {
            if (freezeRemove) {
                log.info("[FP-Manager] Despawning is frozen. Skipping bot despawns.");
                return;
            }
            const toDespawnCount = Math.abs(diff);
            const candidateBots = onlineBots.filter(n => pendingDespawns.indexOf(n) === -1);

            const count = Math.min(toDespawnCount, candidateBots.length);
            if (count > 0) {
                for (let i = 0; i < count; i++) {
                    const name = candidateBots[i];
                    pendingDespawns.push(name);
                    log.info("[FP-Manager] Selected bot to despawn: " + name);

                    const staggerDelay = i * 1.5;
                    task.delay(staggerDelay, () => {
                        task.main(() => {
                            log.info("[FP-Manager] Executing despawn command for: " + name);
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), DESPAWN_CMD.replace("{name}", name));
                            removeActiveBot(name);

                            // Fallback timeout in case bot fails to leave
                            task.delay(5, () => {
                                pendingDespawns = pendingDespawns.filter(n => n !== name);
                            });
                        });
                    });
                }
            }
        } else {
            log.info("[FP-Manager] Bot count is perfectly in sync.");
        }
    } catch (e) {
        log.error("[FP-Manager] Error in reconciliation: " + e);
    }
}

// Event Listeners
const joinListener = registerEvent("org.bukkit.event.player.PlayerJoinEvent", (event) => {
    const player = event.getPlayer();
    const name = player.getName();

    // Check after 0.5s to be safe
    task.delay(0.5, () => {
        if (isBot(player)) {
            log.info("[FP-Manager] Bot join detected: " + name);

            // If the bot is one of our managed bots, make sure it is in activeBots state
            const managedNames = getNamesFromYaml(MANAGED_NAMES_PATH);
            if (managedNames.indexOf(name) !== -1) {
                addActiveBot(name);
                pendingSpawns = pendingSpawns.filter(n => n !== name);
            }

            // Apply rank if managed
            const rank = getBotRank(name);
            if (rank) {
                log.info("[FP-Manager] Applying rank '" + rank + "' to bot: " + name);
                task.main(() => {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp rank " + name + " " + rank);
                });
            }
            return;
        }
        log.info("[FP-Manager] Real player detected: " + name);

        const delay = getRandomInt(5, 10);
        log.info("[FP-Manager] Human join detected (" + name + "). Scheduling spawn/reconciliation in " + delay + "s...");
        task.delay(delay, () => reconcile("Human Join (" + name + ")"));
    });
});

const quitListener = registerEvent("org.bukkit.event.player.PlayerQuitEvent", (event) => {
    const player = event.getPlayer();
    const name = player.getName();

    if (isBot(player)) {
        // If a bot that we manage quits, we should remove it from our active bots list immediately
        removeActiveBot(name);
        pendingDespawns = pendingDespawns.filter(n => n !== name);
        return;
    }

    const delay = getRandomInt(10, 15);
    log.info("[FP-Manager] Human quit detected (" + name + "). Scheduling despawn/reconciliation in " + delay + "s...");
    task.delay(delay, () => reconcile("Human Quit (" + name + ")"));
});

// Init
syncActiveBots();

// Schedule periodic reconciliation loop to fix any out-of-sync states automatically
periodicTask = task.repeat(5, 400, () => {
    reconcile("Periodic Check");
});

/**
 * Scan latest.log for the official "Done" message to trigger startup cleanup
 */
function performStartupCleanup() {
    const ManagementFactory = importClass("java.lang.management.ManagementFactory");
    const uptime = ManagementFactory.getRuntimeMXBean().getUptime();

    // Only proceed if server started recently (< 5 mins) to avoid accidental cleanup on reload
    if (uptime > 300000) return;

    try {
        const File = importClass("java.io.File");
        const RandomAccessFile = importClass("java.io.RandomAccessFile");
        const logFile = new File("logs/latest.log");

        if (!logFile.exists()) return;

        const raf = new RandomAccessFile(logFile, "r");
        const length = logFile.length();
        // Read the last ~2KB of the log file
        const seekPos = Math.max(0, length - 2048);
        raf.seek(seekPos);

        let line;
        let found = false;
        while ((line = raf.readLine()) != null) {
            // Re-encode from ISO-8859-1 to UTF-8 for Java readLine compatibility
            const content = new java.lang.String(line.getBytes("ISO-8859-1"), "UTF-8");

            // Look for the official Bukkit/Paper startup completion message
            if (content.contains("Done") && content.contains("type \"help\"")) {
                found = true;
                break;
            }
        }
        raf.close();

        if (found) {
            log.info("[FP-Manager] Official startup 'Done' message detected in logs. Cleaning up bots...");
            saveActiveBotsState([]); // Clear the persistent state file
            task.main(() => {
                // Force despawn all bots from the plugin to prevent caching issues
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp despawn all");
            });
        }
    } catch (e) {
        log.error("[FP-Manager] Error scanning startup log: " + e);
    }
}

// Run the check on startup (delayed slightly to ensure log is written)
task.delay(100, () => performStartupCleanup());

/**
 * Manually set ranks for all online managed bots
 */
function autoSetRanks(sender) {
    const online = Bukkit.getOnlinePlayers();
    let count = 0;
    for (let i = 0; i < online.size(); i++) {
        const p = online.get(i);
        const name = p.getName();
        if (isBot(p)) {
            const rank = getBotRank(name);
            if (rank) {
                count++;
                task.main(() => {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp rank " + name + " " + rank);
                });
            }
        }
    }
    if (sender) {
        sender.sendMessage("§6[FP-Manager] §7Updated ranks for §e" + count + " §7online bots.");
    } else {
        log.info("[FP-Manager] Auto-set ranks for " + count + " online bots.");
    }
}

addCommand("fpmanager", {
    onCommand: function (sender, args) {
        if (!sender.isOp()) return;

        if (args.length > 0) {
            const sub = args[0].toLowerCase();
            if (sub === "setranks" || sub === "setrank") {
                autoSetRanks(sender);
                return;
            }
            if (sub === "clearstate" || sub === "reset") {
                saveActiveBotsState([]);
                syncActiveBots();
                sender.sendMessage("§6[FP-Manager] §7Active bots state has been cleared.");
                return;
            }
            if (sub === "pause") {
                freezeAdd = true;
                freezeRemove = true;
                sender.sendMessage("§6[FP-Manager] §7ระบบบอทได้รับการ §cหยุดทำงานชั่วคราวทั้งหมด (PAUSE) §7แล้ว จะไม่มีการสปอว์นหรือลบบอกบอท");
                return;
            }
            if (sub === "resume") {
                freezeAdd = false;
                freezeRemove = false;
                sender.sendMessage("§6[FP-Manager] §7ระบบบอทได้รับการ §aเริ่มทำงานตามปกติ (RESUME) §7แล้ว กำลังตรวจสอบสถานะ...");
                reconcile("Manual Command Resume");
                return;
            }
            if (sub === "freezeadd") {
                if (args.length > 1) {
                    freezeAdd = args[1].toLowerCase() === "true";
                } else {
                    freezeAdd = !freezeAdd;
                }
                sender.sendMessage("§6[FP-Manager] §7หยุดการสปอว์นบอทเพิ่ม (Freeze Add): " + (freezeAdd ? "§aเปิดใช้งาน (ไม่เพิ่มบอท)" : "§cปิดใช้งาน"));
                reconcile("Manual Command FreezeAdd");
                return;
            }
            if (sub === "freezeremove") {
                if (args.length > 1) {
                    freezeRemove = args[1].toLowerCase() === "true";
                } else {
                    freezeRemove = !freezeRemove;
                }
                sender.sendMessage("§6[FP-Manager] §7หยุดการลบบอทออก (Freeze Remove): " + (freezeRemove ? "§aเปิดใช้งาน (ไม่ลดบอท)" : "§cปิดใช้งาน"));
                reconcile("Manual Command FreezeRemove");
                return;
            }
            if (sub === "limit" || sub === "max") {
                if (args.length > 1) {
                    const argVal = args[1].toLowerCase();
                    if (argVal === "none" || argVal === "reset" || argVal === "-1") {
                        maxBotCap = -1;
                        sender.sendMessage("§6[FP-Manager] §7ยกเลิกการจำกัดจำนวนบอทสูงสุดแล้ว");
                        reconcile("Limit Reset");
                    } else {
                        const val = parseInt(argVal);
                        if (!isNaN(val) && val >= 0) {
                            maxBotCap = val;
                            sender.sendMessage("§6[FP-Manager] §7จำกัดจำนวนบอทสูงสุดไว้ที่ §e" + maxBotCap + " §7ตัวแล้ว");
                            reconcile("Limit Set (" + maxBotCap + ")");
                        } else {
                            sender.sendMessage("§6[FP-Manager] §cกรุณาระบุจำนวนตัวเลขที่ถูกต้อง เช่น /fpmanager limit 15");
                        }
                    }
                } else {
                    sender.sendMessage("§6[FP-Manager] §7การจำกัดจำนวนบอทสูงสุดในปัจจุบัน: §e" + (maxBotCap === -1 ? "ไม่มีการจำกัด" : maxBotCap + " ตัว"));
                }
                return;
            }
        }

        // GUI Fallback for in-game players
        const Dialog = Services.get("DialogApi");
        const isPlayer = sender instanceof org.bukkit.entity.Player;

        if (isPlayer && Dialog) {
            let tempFreezeAdd = freezeAdd;
            let tempFreezeRemove = freezeRemove;
            let tempMaxCap = maxBotCap;

            const dialog = Dialog.create(sender)
                .title("§6§lFakePlayer Manager §f| §7ตั้งค่าระบบบอท")
                .bodyMessage("info", "§7ปรับแต่งตัวควบคุมระบบบอทตามที่คุณต้องการ:")
                .boolInput("freeze_add", "§c§lหยุดการสปอว์นบอทใหม่ (Freeze Spawning)", freezeAdd)
                .boolInput("freeze_remove", "§c§lหยุดการลบบอทออก (Freeze Despawning)", freezeRemove)
                .rangeInput("max_cap", "§e§lจำกัดบอทสูงสุด (-1 = ไม่จำกัด)", maxBotCap, -1, 100, 1)
                .baseButton("save", "§a§l[ บันทึกการตั้งค่า ]").width(180)
                .exitButton("§7§lปิดหน้าต่าง")
                .columns(1)
                .canCloseWithEscape(true);

            dialog.onEvent(event => {
                const type = String(event.type);
                const id = String(event.id);

                if (type === "input") {
                    const val = event.value;
                    if (id === "freeze_add") {
                        tempFreezeAdd = java.lang.Boolean.TRUE.equals(val) || val === true || String(val) === "true";
                    } else if (id === "freeze_remove") {
                        tempFreezeRemove = java.lang.Boolean.TRUE.equals(val) || val === true || String(val) === "true";
                    } else if (id === "max_cap") {
                        tempMaxCap = parseInt(val);
                        if (isNaN(tempMaxCap)) tempMaxCap = -1;
                    }
                } else if (type === "button" && id === "save") {
                    // Try to read them as fallback from dialog directly
                    try {
                        const readAdd = dialog.get("freeze_add");
                        if (readAdd !== null && readAdd !== undefined) {
                            tempFreezeAdd = java.lang.Boolean.TRUE.equals(readAdd) || readAdd === true || String(readAdd) === "true";
                        }
                    } catch (e) { }
                    try {
                        const readRemove = dialog.get("freeze_remove");
                        if (readRemove !== null && readRemove !== undefined) {
                            tempFreezeRemove = java.lang.Boolean.TRUE.equals(readRemove) || readRemove === true || String(readRemove) === "true";
                        }
                    } catch (e) { }
                    try {
                        const readCap = dialog.get("max_cap");
                        if (readCap !== null && readCap !== undefined) {
                            const parsed = parseInt(readCap);
                            if (!isNaN(parsed)) tempMaxCap = parsed;
                        }
                    } catch (e) { }

                    // Apply the final values
                    freezeAdd = tempFreezeAdd;
                    freezeRemove = tempFreezeRemove;
                    maxBotCap = tempMaxCap;

                    sender.sendMessage("§6[FP-Manager] §a§lบันทึกการตั้งค่าใหม่เรียบร้อยแล้ว!");
                    sender.sendMessage("§6[FP-Manager] §7- หยุดสปอว์นบอทเพิ่ม: " + (freezeAdd ? "§aเปิดใช้งาน (ไม่เพิ่มบอทใหม่)" : "§cปิดใช้งาน (เพิ่มบอทตามปกติ)"));
                    sender.sendMessage("§6[FP-Manager] §7- หยุดลบบอทออก: " + (freezeRemove ? "§aเปิดใช้งาน (ไม่เตะบอทออก)" : "§cปิดใช้งาน (เตะบอทตามปกติ)"));
                    sender.sendMessage("§6[FP-Manager] §7- จำกัดบอทสูงสุด: " + (maxBotCap === -1 ? "§eไม่มีการจำกัด" : "§e" + maxBotCap + " ตัว"));

                    reconcile("GUI Settings Change");
                }
            });

            dialog.show();
            return;
        }

        reconcile("Manual Command Check");
        sender.sendMessage("§6[FP-Manager] §7---------------------------------------");
        sender.sendMessage("§6[FP-Manager] §7หยุดสปอว์นบอทเพิ่ม (Freeze Add): " + (freezeAdd ? "§aเปิดใช้งาน (ไม่เพิ่มบอท)" : "§cปิดใช้งาน"));
        sender.sendMessage("§6[FP-Manager] §7หยุดลบบอทออก (Freeze Remove): " + (freezeRemove ? "§aเปิดใช้งาน (ไม่ลดบอท)" : "§cปิดใช้งาน"));
        sender.sendMessage("§6[FP-Manager] §7จำกัดบอทสูงสุด (Limit): §e" + (maxBotCap === -1 ? "ไม่มีการจำกัด" : maxBotCap + " ตัว"));
        sender.sendMessage("§6[FP-Manager] §7บอทที่กำลังออนไลน์: §e" + activeBots.length);
        sender.sendMessage("§6[FP-Manager] §7บอทกำลังเข้า (Pending Join): §e" + pendingSpawns.length);
        sender.sendMessage("§6[FP-Manager] §7บอทกำลังออก (Pending Quit): §e" + pendingDespawns.length);
        sender.sendMessage("§6[FP-Manager] §7---------------------------------------");
        sender.sendMessage("§6[FP-Manager] §7/fpmanager pause §7- หยุดทั้งเพิ่มและลดบอท");
        sender.sendMessage("§6[FP-Manager] §7/fpmanager resume §7- ให้เพิ่มและลดบอทตามปกติ");
        sender.sendMessage("§6[FP-Manager] §7/fpmanager freezeadd §7- สลับการหยุดเพิ่มบอท");
        sender.sendMessage("§6[FP-Manager] §7/fpmanager freezeremove §7- สลับการหยุดลบบอท");
        sender.sendMessage("§6[FP-Manager] §7/fpmanager limit <จำนวน> §7- จำกัดจำนวนบอทสูงสุด");
        sender.sendMessage("§6[FP-Manager] §7/fpmanager setranks §7- อัปเดตยศบอททั้งหมดที่ออนไลน์");
        sender.sendMessage("§6[FP-Manager] §7/fpmanager reset §7- รีเซ็ตสถานะระบบบอทใหม่");
    }
});

task.bindToUnload(() => {
    unregisterEvent(joinListener);
    unregisterEvent(quitListener);
    removeCommand("fpmanager");
    if (periodicTask) {
        task.cancel(periodicTask);
    }
    log.info("FakePlayerManager unloaded.");
});

log.info("FakePlayerManager loaded (Two-File Logic).");

return true;
