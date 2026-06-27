package com.lecani.afk

import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Scoreboard
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class Afkamrat4Plugin : JavaPlugin() {
    lateinit var dataConfig: FileConfiguration
    private lateinit var dataFile: File
    lateinit var scoreboard: Scoreboard
    lateinit var afkManager: AfkManager
    private var maintenanceTask: BukkitTask? = null
    private var prettySyncTask: BukkitTask? = null
    private var realTimeTrackingTask: BukkitTask? = null
    private var lastDailySaveDate: LocalDate? = null
    private var lastMonthlyResetDate: LocalDate? = null
    private var lastYearlyResetDate: LocalDate? = null
    private var lastRealTimeSyncMillis: Long = 0L
    private val blankNumberFormatApplied = mutableSetOf<String>()
    private val prettyEntriesByObjective = mutableMapOf<String, MutableMap<String, String>>()
    private val prettyTeamsByObjective = mutableMapOf<String, MutableMap<String, String>>()
    private val prettyTimeValueDigits = mutableMapOf<String, Int>()
    private var prettyEntryCounter = 0
    private var prettyTeamCounter = 0

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
        getCommand("permaafk")?.setExecutor(PermaAfkCommand(afkManager))

        afkManager.restoreOnlinePlayerStates()
        afkManager.refreshAllKnownPlayerTeams()
        startMaintenanceScheduler()
        startPrettyObjectiveSync()
        startRealTimeTracking()
    }

    private fun setupScoreboardEntries() {
        clearPrettyRenderTeams()

        // Ensure objectives exist
        ensureObjective("TimeOnline", "TimeOnline")
        ensureObjective("TimeMonth", getMonthDisplayName(LocalDate.now()))
        ensureObjective("TimeYear", getYearDisplayName(LocalDate.now()))
        resetPrettyObjective("PrettyTimeOnline", "Tid (tot)")
        resetPrettyObjective("PrettyTimeMonth", getPrettyMonthDisplayName(LocalDate.now()))
        applyBlankNumberFormat("PrettyTimeOnline")
        applyBlankNumberFormat("PrettyTimeMonth")

        // Ensure teams exist
        ensureTeam("afk") {
            it.prefix = ChatColor.GRAY.toString() + "[AFK] " + ChatColor.RESET
            it.color = org.bukkit.ChatColor.GRAY
        }
        ensureTeam("permaAFK") {
            it.prefix = ChatColor.GRAY.toString() + "[AFK] " + ChatColor.RESET
            it.color = org.bukkit.ChatColor.GRAY
        }
        ensureTeam("ops") {
            it.color = org.bukkit.ChatColor.AQUA
        }
        ensureTeam("new") {
            it.color = org.bukkit.ChatColor.GREEN
        }
        ensureTeam("active") {
            it.color = org.bukkit.ChatColor.GOLD
        }
        ensureTeam("all") {
            it.color = org.bukkit.ChatColor.GREEN
        }
        syncActiveTeamVariant("ops", "opsActive")
        syncActiveTeamVariant("new", "newActive")
        syncActiveTeamVariant("all", "allActive")
    }

    private fun ensureTeam(name: String, configure: ((org.bukkit.scoreboard.Team) -> Unit)? = null): org.bukkit.scoreboard.Team {
        val team = scoreboard.getTeam(name) ?: scoreboard.registerNewTeam(name)
        configure?.invoke(team)
        return team
    }

    private fun syncActiveTeamVariant(baseName: String, variantName: String) {
        val baseTeam = ensureTeam(baseName)
        ensureTeam(variantName) { team ->
            team.color = baseTeam.color
            val baseColor = baseTeam.color?.toString().orEmpty()
            team.prefix = "${ChatColor.GOLD}[A] $baseColor"
        }
    }

    private fun ensureObjective(name: String, displayName: String) {
        val objective = scoreboard.getObjective(name)
        if (objective == null) {
            scoreboard.registerNewObjective(name, "dummy", displayName)
        } else {
            objective.displayName = displayName
        }
    }

    private fun resetPrettyObjective(name: String, displayName: String) {
        if (scoreboard.getObjective(name) == null) {
            ensureObjective(name, displayName)
        } else {
            recreateObjective(name, displayName)
        }
    }

    private fun startMaintenanceScheduler() {
        maintenanceTask?.cancel()
        maintenanceTask = server.scheduler.runTaskTimer(this, Runnable {
            runCatching { performScheduledMaintenance(LocalDateTime.now()) }
                .onFailure { ex -> logger.severe("Scheduled maintenance failed: ${ex.message}") }
        }, 20L, 1200L)
    }

    private fun startPrettyObjectiveSync() {
        prettySyncTask?.cancel()
        prettySyncTask = server.scheduler.runTaskTimer(this, Runnable {
            runCatching { syncPrettyObjectives() }
                .onFailure { ex -> logger.severe("Pretty objective sync failed: ${ex.message}") }
        }, 20L, 100L)
    }

    private fun startRealTimeTracking() {
        realTimeTrackingTask?.cancel()
        lastRealTimeSyncMillis = System.currentTimeMillis()
        realTimeTrackingTask = server.scheduler.runTaskTimer(this, Runnable {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRealTimeSyncMillis
            lastRealTimeSyncMillis = now
            runCatching { afkManager.syncRealTimeTracking(elapsed) }
                .onFailure { ex -> logger.severe("Real-time tracking failed: ${ex.message}") }
        }, 20L, 20L)
    }

    private fun performScheduledMaintenance(now: LocalDateTime) {
        val date = now.toLocalDate()

        if (now.hour == 0 && now.minute == 0) {
            if (now.dayOfMonth == 1 && !date.equals(lastMonthlyResetDate)) {
                recreateObjective("TimeMonth", getMonthDisplayName(date))
                ensureObjective("PrettyTimeMonth", getPrettyMonthDisplayName(date))
                applyBlankNumberFormat("PrettyTimeMonth")
                prettyTimeValueDigits["PrettyTimeMonth"] = 0
                afkManager.resetMonthTrackingRemainders()
                afkManager.refreshAllKnownPlayerTeams()
                lastMonthlyResetDate = date
                logger.info("Reset TimeMonth for ${date.month}.")
            }

            if (now.monthValue == 1 && now.dayOfMonth == 1 && !date.equals(lastYearlyResetDate)) {
                recreateObjective("TimeYear", getYearDisplayName(date))
                afkManager.resetYearTrackingRemainders()
                afkManager.refreshAllKnownPlayerTeams()
                lastYearlyResetDate = date
                logger.info("Reset TimeYear for ${date.year}.")
            }
        }

        if (now.hour == 3 && now.minute == 0 && !date.equals(lastDailySaveDate)) {
            server.dispatchCommand(Bukkit.getConsoleSender(), "save-all")
            lastDailySaveDate = date
            logger.info("Executed scheduled save-all.")
        }
    }

    private fun recreateObjective(name: String, displayName: String) {
        val existing = scoreboard.getObjective(name)
        val displaySlot = existing?.displaySlot
        existing?.unregister()
        blankNumberFormatApplied.remove(name)

        val replacement = scoreboard.registerNewObjective(name, "dummy", displayName)
        if (displaySlot != null) {
            replacement.displaySlot = displaySlot
        }
    }

    private fun getMonthDisplayName(date: LocalDate): String {
        val abbreviations = getMonthAbbreviations()
        val monthLabel = abbreviations.getOrElse(date.monthValue - 1) { DEFAULT_MONTH_ABBREVIATIONS[date.monthValue - 1] }
        return "Minuter ($monthLabel)"
    }

    private fun getYearDisplayName(date: LocalDate): String = "Minuter (${date.year})"

    private fun getPrettyMonthDisplayName(date: LocalDate): String {
        val abbreviations = getMonthAbbreviations()
        val monthLabel = abbreviations.getOrElse(date.monthValue - 1) { DEFAULT_MONTH_ABBREVIATIONS[date.monthValue - 1] }
        return "Tid ($monthLabel)"
    }

    private fun getMonthAbbreviations(): List<String> {
        val configured = config.getStringList("month-abbreviations")
        return if (configured.size == 12) configured else DEFAULT_MONTH_ABBREVIATIONS.toList()
    }

    private fun syncPrettyObjectives() {
        syncPrettyObjective(
            sourceName = "TimeOnline",
            prettyName = "PrettyTimeOnline",
            prettyDisplayName = "Tid (tot)",
            teamPrefix = "pto"
        )
        syncPrettyObjective(
            sourceName = "TimeMonth",
            prettyName = "PrettyTimeMonth",
            prettyDisplayName = getPrettyMonthDisplayName(LocalDate.now()),
            teamPrefix = "ptm"
        )
    }

    private fun syncPrettyObjective(
        sourceName: String,
        prettyName: String,
        prettyDisplayName: String,
        teamPrefix: String
    ) {
        val source = scoreboard.getObjective(sourceName) ?: return
        val pretty = scoreboard.getObjective(prettyName) ?: scoreboard.registerNewObjective(prettyName, "dummy", prettyDisplayName)

        if (pretty.displayName != prettyDisplayName) {
            pretty.displayName = prettyDisplayName
        }

        val sortedEntries = scoreboard.entries
            .asSequence()
            .mapNotNull { entry ->
                val score = source.getScore(entry)
                if (score.isScoreSet) entry to score.score else null
            }
            .filter { (_, minutes) -> minutes != 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .toList()

        val labelsByPlayer = sortedEntries.associate { (playerName, _) ->
            playerName to truncateSidebarLabel(playerName)
        }
        val observedValueDigits = sortedEntries
            .maxOfOrNull { (_, minutes) -> getPrettyTimeDigits(minutes) }
            ?: 0
        val valueDigitsWidth = maxOf(prettyTimeValueDigits.getOrDefault(prettyName, 0), observedValueDigits)
        prettyTimeValueDigits[prettyName] = valueDigitsWidth
        val timesByPlayer = sortedEntries.associate { (playerName, minutes) ->
            playerName to formatCompactPrettyTime(minutes, valueDigitsWidth)
        }
        val activePlayers = mutableSetOf<String>()

        sortedEntries.forEachIndexed { index, (playerName, minutes) ->
            val entryKey = getOrCreatePrettyEntry(prettyName, playerName)
            val teamName = getOrCreatePrettyTeam(prettyName, playerName, teamPrefix)
            val team = scoreboard.getTeam(teamName) ?: scoreboard.registerNewTeam(teamName)

            activePlayers += playerName

            team.entries
                .filter { it != entryKey }
                .forEach { team.removeEntry(it) }
            if (!team.hasEntry(entryKey)) {
                team.addEntry(entryKey)
            }

            val label = labelsByPlayer.getValue(playerName)
            val labelColor = getSidebarNameColor(playerName)
            val timeText = timesByPlayer.getValue(playerName)
            val timeColor = getSidebarTimeColor(playerName)
            val nextPrefix = "$timeColor$timeText${ChatColor.GRAY} "
            val nextSuffix = "$labelColor$label"

            if (team.prefix != nextPrefix) {
                team.prefix = nextPrefix
            }
            if (team.suffix != nextSuffix) {
                team.suffix = nextSuffix
            }

            val score = pretty.getScore(entryKey)
            if (!score.isScoreSet || score.score != minutes) {
                score.score = minutes
            }
        }

        prettyEntriesByObjective[prettyName]
            ?.filterKeys { it !in activePlayers }
            ?.values
            ?.forEach { entryKey ->
                val score = pretty.getScore(entryKey)
                if (score.isScoreSet) {
                    scoreboard.resetScores(entryKey)
                }
            }
    }

    private fun buildPrettyEntryKey(index: Int): String {
        var remaining = index
        val codes = PRETTY_ENTRY_CODES
        val builder = StringBuilder()

        do {
            builder.append(ChatColor.COLOR_CHAR)
            builder.append(codes[remaining % codes.size])
            remaining /= codes.size
        } while (remaining > 0)

        return builder.toString()
    }

    private fun getOrCreatePrettyEntry(prettyName: String, playerName: String): String {
        val entries = prettyEntriesByObjective.getOrPut(prettyName) { mutableMapOf() }
        return entries.getOrPut(playerName) { nextPrettyEntryKey() }
    }

    private fun getOrCreatePrettyTeam(prettyName: String, playerName: String, teamPrefix: String): String {
        val teams = prettyTeamsByObjective.getOrPut(prettyName) { mutableMapOf() }
        return teams.getOrPut(playerName) { nextPrettyTeamName(teamPrefix) }
    }

    private fun nextPrettyEntryKey(): String {
        return buildPrettyEntryNamespace(prettyEntryCounter++)
    }

    private fun buildPrettyEntryNamespace(index: Int): String = "${ChatColor.RESET}${buildPrettyEntryKey(index)}"

    private fun nextPrettyTeamName(teamPrefix: String): String {
        while (true) {
            val suffix = prettyTeamCounter++.toString(36)
            val prefixBudget = (16 - suffix.length).coerceAtLeast(1)
            val candidate = teamPrefix.take(prefixBudget) + suffix
            if (scoreboard.getTeam(candidate) == null) {
                return candidate
            }
        }
    }

    private fun truncateSidebarLabel(name: String): String = if (name.length <= 16) name else name.take(16)

    private fun getSidebarNameColor(playerName: String): String {
        val originalTeam = scoreboard.getEntryTeam(playerName) ?: return ""
        return when {
            originalTeam.name == "afk" || originalTeam.name == "permaAFK" -> ChatColor.GRAY.toString()
            else -> originalTeam.color.toString()
        }
    }

    private fun getSidebarTimeColor(playerName: String): String {
        return if (hasActiveRank(playerName) && !isAfkDisplayEntry(playerName)) {
            ChatColor.GOLD.toString()
        } else {
            ChatColor.RED.toString()
        }
    }

    private fun formatCompactPrettyTime(totalMinutes: Int, valueDigitsWidth: Int): String {
        val clamped = totalMinutes.coerceAtLeast(0)
        val compactValue = getCompactPrettyTimeValue(clamped)
        return "${compactValue.amount.toString().padStart(valueDigitsWidth, ' ')}${compactValue.suffix}"
    }

    private fun getPrettyTimeDigits(totalMinutes: Int): Int {
        return getCompactPrettyTimeValue(totalMinutes.coerceAtLeast(0)).amount.toString().length
    }

    private fun getCompactPrettyTimeValue(totalMinutes: Int): CompactPrettyTimeValue {
        return when {
            totalMinutes >= MINUTES_PER_DAY * DAYS_BEFORE_DAY_DISPLAY -> {
                CompactPrettyTimeValue(totalMinutes / MINUTES_PER_DAY, 'd')
            }
            totalMinutes >= MINUTES_BEFORE_HOUR_DISPLAY -> {
                CompactPrettyTimeValue(totalMinutes / MINUTES_PER_HOUR, 'h')
            }
            else -> CompactPrettyTimeValue(totalMinutes, 'm')
        }
    }

    private fun hasActiveRank(playerName: String): Boolean {
        val monthScore = getObjectiveScore("TimeMonth", playerName)
        val activeThreshold = config.getInt("active-threshold", 1800)
        return monthScore >= activeThreshold
    }

    private fun isAfkDisplayEntry(playerName: String): Boolean {
        val originalTeam = scoreboard.getEntryTeam(playerName) ?: return false
        return originalTeam.name == "afk" || originalTeam.name == "permaAFK"
    }

    private fun getObjectiveScore(objectiveName: String, entry: String): Int {
        val objective = scoreboard.getObjective(objectiveName) ?: return 0
        val score = objective.getScore(entry)
        return if (score.isScoreSet) score.score else 0
    }

    private fun applyBlankNumberFormat(objectiveName: String) {
        if (!blankNumberFormatApplied.add(objectiveName)) return
        runCatching {
            server.dispatchCommand(
                Bukkit.getConsoleSender(),
                "scoreboard objectives modify $objectiveName numberformat blank"
            )
        }.onFailure {
            blankNumberFormatApplied.remove(objectiveName)
        }
    }

    private fun clearPrettyRenderTeams() {
        prettyEntriesByObjective.clear()
        prettyTeamsByObjective.clear()
        prettyTimeValueDigits.clear()
        scoreboard.teams
            .filter { it.name.startsWith("pto") || it.name.startsWith("ptm") }
            .forEach { team -> team.unregister() }
    }

    override fun onDisable() {
        maintenanceTask?.cancel()
        prettySyncTask?.cancel()
        realTimeTrackingTask?.cancel()
        afkManager.flushTrackedMillis()
        // Save persistent AFK data
        dataConfig.save(dataFile)
    }

    companion object {
        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 1_440
        private const val MINUTES_BEFORE_HOUR_DISPLAY = 600
        private const val DAYS_BEFORE_DAY_DISPLAY = 10
        private val PRETTY_ENTRY_CODES = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'a', 'b', 'c', 'd', 'e', 'f', 'k', 'l', 'm', 'n', 'o', 'r'
        )
        private val DEFAULT_MONTH_ABBREVIATIONS = listOf(
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"
        )
    }

    private data class CompactPrettyTimeValue(
        val amount: Int,
        val suffix: Char
    )
}
