package com.lecani.afk

import org.bukkit.GameRule
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBedLeaveEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import kotlin.math.ceil

class AfkListener(private val manager: AfkManager) : Listener {

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.yaw != to.yaw || from.pitch != to.pitch) {
            manager.recordActivity(event.player)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!manager.isAfk(player)) {
            manager.assignPlayerTeam(player)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (!manager.isAfk(player)) {
            manager.recordActivity(player)
        }
    }

    @EventHandler
    fun onBedEnter(event: PlayerBedEnterEvent) {
        if (event.bedEnterResult == PlayerBedEnterEvent.BedEnterResult.OK) {
            val world = event.player.world
            updateSleepStatus(world.players, world.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE) ?: 100)
        }
    }

    @EventHandler
    fun onBedLeave(event: PlayerBedLeaveEvent) {
        val world = event.player.world
        if (world.time < 12541) return
        updateSleepStatus(world.players, world.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE) ?: 100)
    }

    private fun updateSleepStatus(players: Collection<Player>, perc: Int) {
        val active = players.filterNot { manager.isAfk(it) }
        if (active.isEmpty()) return
        val sleepingCount = active.count { it.isSleeping }
        val required = ceil(perc / 100.0 * active.size).toInt().coerceAtLeast(1)
        val defaultTmpl = "%count%/%required% players sleeping (AFK not counted)."
        val tmpl = manager.plugin.config.getString("sleep-status-message")
            ?: defaultTmpl
        val msg = tmpl
            .replace("%count%", sleepingCount.toString())
            .replace("%required%", required.toString())
    }
}