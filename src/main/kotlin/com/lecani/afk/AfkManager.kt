package com.lecani.afk

import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Scoreboard
import java.io.File
import java.io.IOException
import java.util.UUID

class AfkManager(
    val plugin: Afkamrat4Plugin,
    private val scoreboard: Scoreboard,
    private val dataConfig: FileConfiguration
) {
    private val afkPlayers = mutableSetOf<UUID>()
    private val manualAfkPlayers = mutableSetOf<UUID>()
    private val permaAfkPlayers = mutableSetOf<UUID>()
    private val trackedOnlineMillis = mutableMapOf<UUID, Long>()
    private val trackedMonthMillis = mutableMapOf<UUID, Long>()
    private val trackedYearMillis = mutableMapOf<UUID, Long>()
    private val warnTasks = mutableMapOf<UUID, BukkitTask>()
    private val afkTasks = mutableMapOf<UUID, BukkitTask>()

    private val warnTicks: Long
    private val afkTicks: Long
    private val penaltyPoints: Int

    init {
        val warnSec = plugin.config.getInt("warn-after", 180)
        val afkSec = plugin.config.getInt("afk-after", 300)
        warnTicks = warnSec * 20L
        afkTicks = afkSec * 20L
        penaltyPoints = plugin.config.getInt("afk-penalty-points", 5)
        loadPersistedState()
    }

    fun restoreOnlinePlayerStates() {
        Bukkit.getOnlinePlayers().forEach { player ->
            val uid = player.uniqueId
            if (isPermaAfk(uid)) {
                applyPermaAfkState(player)
            } else if (isAfk(uid)) {
                val manual = isManualAfk(uid)
                setPlayerAfk(player, manual, silent = true)
            } else {
                assignPlayerTeam(player)
                recordActivity(player)
            }
        }
    }

    fun isAfk(uuid: UUID) = afkPlayers.contains(uuid)
    fun isAfk(player: Player) = isAfk(player.uniqueId)
    fun isManualAfk(uuid: UUID) = manualAfkPlayers.contains(uuid)
    fun isPermaAfk(uuid: UUID) = permaAfkPlayers.contains(uuid)
    fun isPermaAfk(player: Player) = isPermaAfk(player.uniqueId)

    fun handleJoin(player: Player) {
        if (isPermaAfk(player)) {
            applyPermaAfkState(player)
            return
        }

        if (isAfk(player)) {
            setPlayerAfk(player, manual = isManualAfk(player.uniqueId), silent = true)
        } else {
            assignPlayerTeam(player)
            recordActivity(player)
        }
    }

    fun handleQuit(player: Player) {
        val uid = player.uniqueId
        resetAfkTimers(uid)

        if (!isPermaAfk(uid)) {
            afkPlayers.remove(uid)
            manualAfkPlayers.remove(uid)
            dataConfig.set("afkPlayers.$uid", null)
            player.isSleepingIgnored = false
        }

        saveTrackedMillis(uid)
        saveDataFile()
    }

    fun recordActivity(player: Player) {
        if (!player.isOnline) return
        val uid = player.uniqueId

        if (isPermaAfk(uid)) {
            applyPermaAfkState(player)
            return
        }

        if (!isAfk(uid)) {
            resetAfkTimers(uid)
            startAfkTimers(player)
        } else if (!isManualAfk(uid)) {
            setPlayerActive(player)
        }
    }

    fun syncRealTimeTracking(elapsedMillis: Long) {
        if (elapsedMillis <= 0L) return

        Bukkit.getOnlinePlayers()
            .filter { it.isOnline && !isAfk(it) }
            .forEach { player ->
                val uid = player.uniqueId
                val onlineMinutes = addElapsedMillis(trackedOnlineMillis, uid, elapsedMillis)
                val monthMinutes = addElapsedMillis(trackedMonthMillis, uid, elapsedMillis)
                val yearMinutes = addElapsedMillis(trackedYearMillis, uid, elapsedMillis)

                if (onlineMinutes > 0) {
                    incrementObjective("TimeOnline", player.name, onlineMinutes)
                }
                if (monthMinutes > 0) {
                    incrementObjective("TimeMonth", player.name, monthMinutes)
                }
                if (yearMinutes > 0) {
                    incrementObjective("TimeYear", player.name, yearMinutes)
                }
                if (onlineMinutes > 0 || monthMinutes > 0 || yearMinutes > 0) {
                    assignPlayerTeam(player)
                }
            }
    }

    fun setPlayerPermaAfk(player: Player, silent: Boolean = false) {
        val uid = player.uniqueId
        permaAfkPlayers.add(uid)
        afkPlayers.add(uid)
        manualAfkPlayers.remove(uid)
        ensureTrackedMillis(uid)

        resetAfkTimers(uid)
        scoreboard.getTeam("afk")?.removeEntry(player.name)
        scoreboard.getTeam("permaAFK")?.addEntry(player.name)
        removeFromNormalTeams(player.name)

        player.isSleepingIgnored = true
        dataConfig.set("permaAfkPlayers.$uid", true)
        dataConfig.set("afkPlayers.$uid", null)
        saveDataFile()

        if (!silent) {
            Bukkit.broadcastMessage("${player.name} is now permanently AFK.")
        }
    }

    fun clearPermaAfk(player: Player, silent: Boolean = false) {
        val uid = player.uniqueId
        permaAfkPlayers.remove(uid)
        afkPlayers.remove(uid)
        manualAfkPlayers.remove(uid)
        resetAfkTimers(uid)

        scoreboard.getTeam("permaAFK")?.removeEntry(player.name)
        player.isSleepingIgnored = false
        dataConfig.set("permaAfkPlayers.$uid", null)
        saveDataFile()

        assignPlayerTeam(player)
        recordActivity(player)

        if (!silent) {
            Bukkit.broadcastMessage("${player.name} is no longer permanently AFK.")
        }
    }

    fun setPlayerAfk(player: Player, manual: Boolean, silent: Boolean) {
        if (!player.isOnline || isPermaAfk(player)) return

        val uid = player.uniqueId
        afkPlayers.add(uid)
        if (manual) {
            manualAfkPlayers.add(uid)
        } else {
            manualAfkPlayers.remove(uid)
        }

        scoreboard.getTeam("permaAFK")?.removeEntry(player.name)
        scoreboard.getTeam("afk")?.addEntry(player.name)
        removeFromNormalTeams(player.name)
        player.isSleepingIgnored = true

        dataConfig.set("afkPlayers.${uid}.manual", manual)
        saveDataFile()

        if (!manual) {
            listOf("TimeOnline", "TimeMonth", "TimeYear").forEach { objName ->
                scoreboard.getObjective(objName)?.let { obj ->
                    val score = obj.getScore(player.name)
                    score.score = score.score - penaltyPoints
                }
            }
        }

        resetAfkTimers(uid)
        if (!silent) {
            val key = if (manual) "manual-afk-message" else "afk-message"
            val defaultMsg = "%player% is now AFK."
            val tmpl = plugin.config.getString(key) ?: defaultMsg
            val msg = tmpl.replace("%player%", player.name)
            Bukkit.broadcastMessage(msg)
        }
    }

    fun setPlayerActive(player: Player) {
        val uid = player.uniqueId
        if (isPermaAfk(uid)) return

        val wasManual = isManualAfk(uid)
        afkPlayers.remove(uid)
        manualAfkPlayers.remove(uid)
        resetAfkTimers(uid)

        scoreboard.getTeam("afk")?.removeEntry(player.name)
        player.isSleepingIgnored = false
        dataConfig.set("afkPlayers.$uid", null)
        saveDataFile()

        assignPlayerTeam(player)
        startAfkTimers(player)

        val key = if (wasManual) "manual-return-message" else "return-message"
        val defaultReturn = "%player% is no longer AFK."
        val tmpl = plugin.config.getString(key) ?: defaultReturn
        val msg = tmpl.replace("%player%", player.name)
        Bukkit.broadcastMessage(msg)
    }

    fun assignPlayerTeam(player: Player) {
        assignEntryTeam(player.name, player.isOp)
    }

    fun refreshAllKnownPlayerTeams() {
        scoreboard.entries
            .asSequence()
            .filter { entry -> isTrackedPlayerEntry(entry) }
            .forEach { entry ->
                if (scoreboard.getTeam("afk")?.hasEntry(entry) == true || scoreboard.getTeam("permaAFK")?.hasEntry(entry) == true) {
                    return@forEach
                }

                val onlinePlayer = Bukkit.getPlayerExact(entry)
                val isOperator = onlinePlayer?.isOp ?: Bukkit.getOfflinePlayer(entry).isOp
                assignEntryTeam(entry, isOperator)
            }
    }

    private fun assignEntryTeam(name: String, isOperator: Boolean) {
        if (isAfkName(name)) {
            return
        }

        removeFromNormalTeams(name)
        scoreboard.getTeam("afk")?.removeEntry(name)
        scoreboard.getTeam("permaAFK")?.removeEntry(name)

        if (isOperator) {
            val monthScore = scoreboard.getObjective("TimeMonth")?.getScore(name)?.score ?: 0
            val activeThresh = plugin.config.getInt("active-threshold", 1800)
            val teamName = if (monthScore >= activeThresh) "opsActive" else "ops"
            scoreboard.getTeam(teamName)?.addEntry(name)
        } else {
            val onlineScore = scoreboard.getObjective("TimeOnline")?.getScore(name)?.score ?: 0
            val monthScore = scoreboard.getObjective("TimeMonth")?.getScore(name)?.score ?: 0
            val newThresh = plugin.config.getInt("new-threshold", 1000)
            val activeThresh = plugin.config.getInt("active-threshold", 1800)
            when {
                onlineScore < newThresh -> scoreboard.getTeam("new")?.addEntry(name)
                monthScore >= activeThresh -> scoreboard.getTeam("allActive")?.addEntry(name)
                else -> scoreboard.getTeam("all")?.addEntry(name)
            }
        }
    }

    private fun isTrackedPlayerEntry(entry: String): Boolean {
        if (entry.contains(org.bukkit.ChatColor.COLOR_CHAR)) {
            return false
        }

        return TRACKED_OBJECTIVES.any { objectiveName ->
            scoreboard.getObjective(objectiveName)?.getScore(entry)?.isScoreSet == true
        }
    }

    private fun isAfkName(name: String): Boolean {
        if (scoreboard.getTeam("afk")?.hasEntry(name) == true || scoreboard.getTeam("permaAFK")?.hasEntry(name) == true) {
            return true
        }

        val onlinePlayer = Bukkit.getPlayerExact(name) ?: return false
        return isAfk(onlinePlayer)
    }

    fun flushTrackedMillis() {
        trackedOnlineMillis.keys
            .plus(trackedMonthMillis.keys)
            .plus(trackedYearMillis.keys)
            .distinct()
            .forEach { uid -> saveTrackedMillis(uid) }
        saveDataFile()
    }

    fun resetMonthTrackingRemainders() {
        trackedMonthMillis.keys.toList().forEach { uid ->
            trackedMonthMillis[uid] = 0L
            dataConfig.set("timeRemainders.month.$uid", 0L)
        }
        saveDataFile()
    }

    fun resetYearTrackingRemainders() {
        trackedYearMillis.keys.toList().forEach { uid ->
            trackedYearMillis[uid] = 0L
            dataConfig.set("timeRemainders.year.$uid", 0L)
        }
        saveDataFile()
    }

    private fun applyPermaAfkState(player: Player) {
        afkPlayers.add(player.uniqueId)
        manualAfkPlayers.remove(player.uniqueId)
        resetAfkTimers(player.uniqueId)
        scoreboard.getTeam("afk")?.removeEntry(player.name)
        scoreboard.getTeam("permaAFK")?.addEntry(player.name)
        removeFromNormalTeams(player.name)
        player.isSleepingIgnored = true
    }

    private fun removeFromNormalTeams(name: String) {
        listOf("ops", "opsActive", "new", "newActive", "active", "all", "allActive").forEach {
            scoreboard.getTeam(it)?.removeEntry(name)
        }
    }

    private fun startAfkTimers(player: Player) {
        val uid = player.uniqueId
        resetAfkTimers(uid)
        val defaultWarn = "%player% has been idle for 3 minutes."
        val warnTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!isAfk(uid) && player.isOnline) {
                val tmpl = plugin.config.getString("warning-message") ?: defaultWarn
                val msg = tmpl.replace("%player%", player.name)
                Bukkit.broadcastMessage(msg)
            }
        }, warnTicks)
        warnTasks[uid] = warnTask

        val afkTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!isAfk(uid) && !isPermaAfk(uid)) {
                setPlayerAfk(player, manual = false, silent = false)
            }
        }, afkTicks)
        afkTasks[uid] = afkTask
    }

    fun resetAfkTimers(uid: UUID) {
        warnTasks.remove(uid)?.cancel()
        afkTasks.remove(uid)?.cancel()
    }

    private fun incrementObjective(name: String, entry: String, amount: Int) {
        val objective = scoreboard.getObjective(name) ?: return
        val score = objective.getScore(entry)
        score.score = score.score + amount
    }

    private fun addElapsedMillis(store: MutableMap<UUID, Long>, uid: UUID, elapsedMillis: Long): Int {
        val totalMillis = store.getOrDefault(uid, 0L) + elapsedMillis
        val wholeMinutes = (totalMillis / MILLIS_PER_MINUTE).toInt()
        store[uid] = totalMillis % MILLIS_PER_MINUTE
        return wholeMinutes
    }

    private fun ensureTrackedMillis(uid: UUID) {
        trackedOnlineMillis.putIfAbsent(uid, 0L)
        trackedMonthMillis.putIfAbsent(uid, 0L)
        trackedYearMillis.putIfAbsent(uid, 0L)
    }

    private fun saveTrackedMillis(uid: UUID) {
        dataConfig.set("timeRemainders.online.$uid", trackedOnlineMillis.getOrDefault(uid, 0L))
        dataConfig.set("timeRemainders.month.$uid", trackedMonthMillis.getOrDefault(uid, 0L))
        dataConfig.set("timeRemainders.year.$uid", trackedYearMillis.getOrDefault(uid, 0L))
    }

    private fun loadPersistedState() {
        dataConfig.getConfigurationSection("afkPlayers")
            ?.getKeys(false)
            ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.forEach { uid ->
                afkPlayers.add(uid)
                if (dataConfig.getBoolean("afkPlayers.$uid.manual")) {
                    manualAfkPlayers.add(uid)
                }
            }

        dataConfig.getConfigurationSection("permaAfkPlayers")
            ?.getKeys(false)
            ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.forEach { uid ->
                permaAfkPlayers.add(uid)
                afkPlayers.add(uid)
            }

        loadRemainders("online", trackedOnlineMillis)
        loadRemainders("month", trackedMonthMillis)
        loadRemainders("year", trackedYearMillis)
    }

    private fun loadRemainders(sectionName: String, target: MutableMap<UUID, Long>) {
        dataConfig.getConfigurationSection("timeRemainders.$sectionName")
            ?.getKeys(false)
            ?.mapNotNull { key ->
                val uid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
                uid to dataConfig.getLong("timeRemainders.$sectionName.$key")
            }
            ?.forEach { (uid, millis) ->
                target[uid] = millis.coerceAtLeast(0L)
            }
    }

    private fun saveDataFile() {
        try {
            dataConfig.save(File(plugin.dataFolder, "afk-data.yml"))
        } catch (ex: IOException) {
            plugin.logger.severe("Could not save AFK data: ${ex.message}")
        }
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private val TRACKED_OBJECTIVES = listOf("TimeOnline", "TimeMonth", "TimeYear")
    }
}
