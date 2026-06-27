package com.lecani.afk

import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Scoreboard
import java.io.File
import java.util.UUID

class Afkamrat : JavaPlugin() {
    lateinit var dataConfig: FileConfiguration
    private lateinit var dataFile: File
    lateinit var scoreboard: Scoreboard
    lateinit var afkManager: AfkManager

    override fun onEnable() {
        // Load config
        saveDefaultConfig()
        reloadConfig()

        // Setup scoreboard
        scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        setupScoreboardEntries()

        // Prepare data file
        dataFile = File(dataFolder, "afk-data.yml")
        if (!dataFile.exists()) {
            dataFile.parentFile.mkdirs()
            dataFile.createNewFile()
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile)

        // Initialize manager and listeners/commands
        afkManager = AfkManager(this, scoreboard, dataConfig)
        server.pluginManager.registerEvents(AfkListener(afkManager), this)
        getCommand("afk")?.setExecutor(AfkCommand(afkManager))
        getCommand("back")?.setExecutor(BackCommand(afkManager))

        // Restore AFK status silently
        dataConfig.getConfigurationSection("afkPlayers")
            ?.getKeys(false)
            ?.forEach { uuidStr ->
                runCatching {
                    val uuid = UUID.fromString(uuidStr)
                    val player = Bukkit.getPlayer(uuid)
                    val manual = dataConfig.getBoolean("afkPlayers.$uuidStr.manual")
                    if (player != null && player.isOnline) {
                        afkManager.setPlayerAfk(player, manual, silent = true)
                    }
                }
            }
    }

    private fun setupScoreboardEntries() {
        // Ensure objectives exist
        listOf("TimeOnline", "TimeMonth", "TimeYear").forEach { name ->
            if (scoreboard.getObjective(name) == null) {
                scoreboard.registerNewObjective(name, "dummy", name)
            }
        }
        // Ensure teams exist
        listOf("afk", "ops", "new", "active", "all").forEach { name ->
            scoreboard.getTeam(name)
                ?: scoreboard.registerNewTeam(name).apply {
                    if (name == "afk") {
                        prefix = ChatColor.GRAY.toString() + "[AFK] " + ChatColor.RESET
                    }
                }
        }
    }

    override fun onDisable() {
        // Save persistent AFK data
        dataConfig.save(dataFile)
    }
}