package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED))
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
            "reload" -> reloadPlugin(sender)
            else -> sender.sendMessage(Component.text("Unknown subcommand. Use /sp help for a list of commands.", NamedTextColor.RED))
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        val completions = mutableListOf<String>()
        if (args.size == 1) {
            completions.addAll(listOf("help", "list", "toggle", "reload").filter { it.startsWith(args[0], ignoreCase = true) })
        } else if (args.size == 2 && args[0].equals("toggle", ignoreCase = true)) {
            completions.addAll(plugin.moduleManager.getModules().map { it.getName() }.filter { it.startsWith(args[1], ignoreCase = true) })
        }
        return completions
    }

    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage(Component.text("--- SurvivalPlus Help ---", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("/sp help ", NamedTextColor.YELLOW).append(Component.text("- Shows this help message.", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/sp list ", NamedTextColor.YELLOW).append(Component.text("- Lists all modules and their status.", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/sp toggle <module> ", NamedTextColor.YELLOW).append(Component.text("- Toggles a module on or off.", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/sp reload ", NamedTextColor.YELLOW).append(Component.text("- Reloads the plugin's configuration.", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/spmenu ", NamedTextColor.YELLOW).append(Component.text("- Opens the GUI menu.", NamedTextColor.GRAY)))
    }

    private fun reloadPlugin(sender: CommandSender) {
        plugin.moduleManager.reloadModules()
        sender.sendMessage(Component.text("SurvivalPlus configuration reloaded.", NamedTextColor.GREEN))
    }

    private fun listModules(sender: CommandSender) {
        sender.sendMessage(Component.text("--- Modules ---", NamedTextColor.GOLD))
        plugin.moduleManager.getModules().forEach { module ->
            val isEnabled = plugin.moduleManager.isModuleEnabled(module.getName())
            val status = if (isEnabled) {
                Component.text("Enabled", NamedTextColor.GREEN)
            } else {
                Component.text("Disabled", NamedTextColor.RED)
            }
            sender.sendMessage(Component.text("${module.getName()}: ", NamedTextColor.YELLOW).append(status))
        }
    }

    private fun toggleModule(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /sp toggle <module>", NamedTextColor.RED))
            return
        }
        val moduleName = args[1]
        if (plugin.moduleManager.getModule(moduleName) != null) {
            plugin.moduleManager.toggleModule(moduleName)
            val isEnabled = plugin.moduleManager.isModuleEnabled(moduleName)
            val status = if (isEnabled) "enabled" else "disabled"
            sender.sendMessage(Component.text("Module '$moduleName' has been $status.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("Module '$moduleName' not found.", NamedTextColor.RED))
        }
    }
}
