package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus
import com.yourdomain.survivalplus.modules.Module
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class GUIManager(private val plugin: SurvivalPlus) : Listener, CommandExecutor {

    private val inventoryTitle = Component.text("SurvivalPlus Modules", NamedTextColor.DARK_AQUA)
    private val moduleKey = NamespacedKey(plugin, "module_name")

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
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED))
            return true
        }
        openMainMenu(sender)
        return true
    }

    private fun openMainMenu(player: Player) {
        val inventory = Bukkit.createInventory(player, 9, inventoryTitle)
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

        meta?.displayName(Component.text(module.getName(), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))

        val lore = mutableListOf<Component>()
        lore.add(Component.text(module.getDescription(), NamedTextColor.GRAY))
        lore.add(Component.empty())
        lore.add(
            if (isEnabled) Component.text("Status: Enabled", NamedTextColor.GREEN)
            else Component.text("Status: Disabled", NamedTextColor.RED)
        )
        lore.add(Component.text("Click to toggle!", NamedTextColor.YELLOW))
        meta?.lore(lore)

        meta?.persistentDataContainer?.set(moduleKey, PersistentDataType.STRING, module.getName())

        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title() != inventoryTitle) return
        event.isCancelled = true

        val clickedItem = event.currentItem ?: return
        val player = event.whoClicked as? Player ?: return

        val moduleName = clickedItem.itemMeta?.persistentDataContainer?.get(moduleKey, PersistentDataType.STRING)
        if (moduleName != null) {
            plugin.moduleManager.toggleModule(moduleName)
            openMainMenu(player) // Re-open to update the GUI
        }
    }
}
