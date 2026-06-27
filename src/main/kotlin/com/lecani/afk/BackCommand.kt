package com.lecani.afk.afkamrat4Plugin

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class BackCommand(private val manager: AfkManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        val player = sender as Player
        if (!manager.isAfk(player)) {
            player.sendMessage("${ChatColor.YELLOW}You are not AFK.")
            return true
        }
        manager.setPlayerActive(player)
        return true
    }
}