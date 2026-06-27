package com.lecani.afk.afkamrat4Plugin

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
    }

    fun isAfk(uuid: UUID) = afkPlayers.contains(uuid)
    fun isAfk(player: Player) = isAfk(player.uniqueId)
    fun isManualAfk(uuid: UUID) = manualAfkPlayers.contains(uuid)

    fun recordActivity(player: Player) {
        val uid = player.uniqueId
        if (!isAfk(uid)) {
            resetAfkTimers(uid)
            startAfkTimers(player)
        } else if (!isManualAfk(uid)) {
            setPlayerActive(player)
        }
    }

    private fun startAfkTimers(player: Player) {
        val uid = player.uniqueId
        resetAfkTimers(uid)
        val defaultWarn = "%player% has been idle for 3 minutes."
        val warnTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!isAfk(uid)) {
                val tmpl = plugin.config.getString("warning-message") ?: defaultWarn
                val msg = tmpl.replace("%player%", player.name)
                Bukkit.broadcastMessage(msg)
            }
        }, warnTicks)
        warnTasks[uid] = warnTask

        val afkTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!isAfk(uid)) {
                setPlayerAfk(player, manual = false, silent = false)
            }
        }, afkTicks)
        afkTasks[uid] = afkTask
    }

    private fun resetAfkTimers(uid: UUID) {
        warnTasks.remove(uid)?.cancel()
        afkTasks.remove(uid)?.cancel()
    }

    fun setPlayerAfk(player: Player, manual: Boolean, silent: Boolean) {
        val uid = player.uniqueId
        afkPlayers.add(uid)
        if (manual) manualAfkPlayers.add(uid)
        scoreboard.getTeam("afk")?.addEntry(player.name)
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
        val wasManual = isManualAfk(uid)
        afkPlayers.remove(uid)
        manualAfkPlayers.remove(uid)
        resetAfkTimers(uid)
        scoreboard.getTeam("afk")?.removeEntry(player.name)
        player.isSleepingIgnored = false
        dataConfig.set("afkPlayers.$uid", null)
        saveDataFile()

        assignPlayerTeam(player)

        val key = if (wasManual) "manual-return-message" else "return-message"
        val defaultReturn = "%player% is no longer AFK."
        val tmpl = plugin.config.getString(key) ?: defaultReturn
        val msg = tmpl.replace("%player%", player.name)
        Bukkit.broadcastMessage(msg)
    }

    fun assignPlayerTeam(player: Player) {
        val name = player.name
        listOf("ops", "new", "active", "all").forEach { scoreboard.getTeam(it)?.removeEntry(name) }
        if (player.isOp) {
            scoreboard.getTeam("ops")?.addEntry(name)
        } else {
            val onlineScore = scoreboard.getObjective("TimeOnline")?.getScore(name)?.score ?: 0
            val monthScore = scoreboard.getObjective("TimeMonth")?.getScore(name)?.score ?: 0
            val newThresh = plugin.config.getInt("new-threshold", 1000)
            val activeThresh = plugin.config.getInt("active-threshold", 2000)
            when {
                onlineScore < newThresh -> scoreboard.getTeam("new")?.addEntry(name)
                monthScore > activeThresh -> scoreboard.getTeam("active")?.addEntry(name)
                else -> scoreboard.getTeam("all")?.addEntry(name)
            }
        }
    }

    private fun saveDataFile() {
        try {
            dataConfig.save(File(plugin.dataFolder, "afk-data.yml"))
        } catch (ex: IOException) {
            plugin.logger.severe("Could not save AFK data: ${ex.message}")
        }
    }
}