package com.lecani.afk

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class PermaAfkCommand(private val manager: AfkManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }

        val player = sender as Player
        if (manager.isPermaAfk(player)) {
            manager.clearPermaAfk(player)
            player.sendMessage("${ChatColor.GREEN}Permanent AFK disabled.")
        } else {
            manager.setPlayerPermaAfk(player)
            player.sendMessage("${ChatColor.GREEN}Permanent AFK enabled.")
        }
        return true
    }
}
