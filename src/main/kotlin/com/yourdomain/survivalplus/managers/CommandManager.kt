package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class CommandManager(private val plugin: SurvivalPlus) : CommandExecutor, TabCompleter {

    fun registerCommands() {
        plugin.getCommand("survivalplus")?.setExecutor(this)
        plugin.getCommand("survivalplus")?.setTabCompleter(this)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("survivalplus.admin")) {
            sender.sendMessage("${ChatColor.RED}You do not have permission to use this command.")
            return true
        }

        if (args.isEmpty()) {
            sendHelpMessage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "help" -> sendHelpMessage(sender)
            "list" -> listModules(sender)
            "toggle" -> toggleModule(sender, args)
            else -> sender.sendMessage("${ChatColor.RED}Unknown subcommand. Use /sp help for a list of commands.")
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        val completions = mutableListOf<String>()
        if (args.size == 1) {
            completions.addAll(listOf("help", "list", "toggle").filter { it.startsWith(args[0], ignoreCase = true) })
        } else if (args.size == 2 && args[0].equals("toggle", ignoreCase = true)) {
            completions.addAll(plugin.moduleManager.getModules().map { it.getName() }.filter { it.startsWith(args[1], ignoreCase = true) })
        }
        return completions
    }

    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage("${ChatColor.GOLD}--- SurvivalPlus Help ---")
        sender.sendMessage("${ChatColor.YELLOW}/sp help ${ChatColor.GRAY}- Shows this help message.")
        sender.sendMessage("${ChatColor.YELLOW}/sp list ${ChatColor.GRAY}- Lists all modules and their status.")
        sender.sendMessage("${ChatColor.YELLOW}/sp toggle <module> ${ChatColor.GRAY}- Toggles a module on or off.")
        sender.sendMessage("${ChatColor.YELLOW}/spmenu ${ChatColor.GRAY}- Opens the GUI menu.")
    }

    private fun listModules(sender: CommandSender) {
        sender.sendMessage("${ChatColor.GOLD}--- Modules ---")
        plugin.moduleManager.getModules().forEach { module ->
            val status = if (plugin.moduleManager.isModuleEnabled(module.getName())) {
                "${ChatColor.GREEN}Enabled"
            } else {
                "${ChatColor.RED}Disabled"
            }
            sender.sendMessage("${ChatColor.YELLOW}${module.getName()}: $status")
        }
    }

    private fun toggleModule(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage("${ChatColor.RED}Usage: /sp toggle <module>")
            return
        }
        val moduleName = args[1]
        if (plugin.moduleManager.toggleModule(moduleName)) {
            val status = if (plugin.moduleManager.isModuleEnabled(moduleName)) "enabled" else "disabled"
            sender.sendMessage("${ChatColor.GREEN}Module '${moduleName}' has been $status.")
        } else {
            sender.sendMessage("${ChatColor.RED}Module '${moduleName}' not found.")
        }
    }
}
