package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus
import com.yourdomain.survivalplus.modules.Module
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

class GUIManager(private val plugin: SurvivalPlus) : Listener, CommandExecutor {

    private val inventoryTitle = "${ChatColor.DARK_AQUA}SurvivalPlus Modules"

    fun registerGUI() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.getCommand("spmenu")?.setExecutor(this)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("This command can only be used by players.")
            return true
        }
        if (!sender.hasPermission("survivalplus.menu")) {
            sender.sendMessage("${ChatColor.RED}You do not have permission to use this command.")
            return true
        }
        openMainMenu(sender)
        return true
    }

    private fun openMainMenu(player: Player) {
        val inventory = Bukkit.createInventory(null, 9, inventoryTitle)
        plugin.moduleManager.getModules().forEachIndexed { index, module ->
            if (index < 9) {
                inventory.setItem(index, createModuleItem(module))
            }
        }
        player.openInventory(inventory)
    }

    private fun createModuleItem(module: Module): ItemStack {
        val isEnabled = plugin.moduleManager.isModuleEnabled(module.getName())
        val material = if (isEnabled) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName("${ChatColor.BOLD}${module.getName()}")
        val lore = mutableListOf<String>()
        lore.add("${ChatColor.GRAY}${module.getDescription()}")
        lore.add("")
        lore.add(if (isEnabled) "${ChatColor.GREEN}Status: Enabled" else "${ChatColor.RED}Status: Disabled")
        lore.add("${ChatColor.YELLOW}Click to toggle!")
        meta?.lore = lore
        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title != inventoryTitle) return
        event.isCancelled = true

        val clickedItem = event.currentItem ?: return
        val player = event.whoClicked as? Player ?: return

        val moduleName = clickedItem.itemMeta?.displayName?.let { ChatColor.stripColor(it) }
        if (moduleName != null) {
            plugin.moduleManager.toggleModule(moduleName)
            // A bit inefficient to reopen the whole inventory, a simple update would be better
            // but for a small inventory like this, it's acceptable and simpler.
            openMainMenu(player)
        }
    }
}
