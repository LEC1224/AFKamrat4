package com.lecani.afk.afkamrat4Plugin

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class AfkCommand(private val manager: AfkManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }
        val player = sender as Player
        if (manager.isAfk(player)) {
            if (manager.isManualAfk(player.uniqueId)) {
                player.sendMessage("${ChatColor.YELLOW}You are already AFK.")
            } else {
                manager.setPlayerActive(player)
            }
            return true
        }
        manager.setPlayerAfk(player, manual = true, silent = false)
        return true
    }
}