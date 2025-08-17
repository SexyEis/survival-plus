package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

class ToolStorageGUI(private val plugin: SurvivalPlus, private val toolStorageModule: ToolStorageModule) : Listener {

    private val inventoryTitle = Component.text("Tool Storage")

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(player: Player, tool: ItemStack) {
        val inventory = Bukkit.createInventory(player, 54, inventoryTitle)
        val toolInventory = toolStorageModule.getToolInventory(tool)

        toolInventory.forEach { (material, amount) ->
            val item = ItemStack(material)
            val meta = item.itemMeta
            meta?.lore(listOf(Component.text("Amount: $amount", NamedTextColor.GRAY)))
            item.itemMeta = meta
            inventory.addItem(item)
        }
        player.openInventory(inventory)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title() != inventoryTitle) return
        event.isCancelled = true

        val clickedItem = event.currentItem ?: return
        val player = event.whoClicked as? Player ?: return
        val tool = player.inventory.itemInMainHand

        val toolInventory = toolStorageModule.getToolInventory(tool)
        val material = clickedItem.type
        var amount = toolInventory[material] ?: 0

        val amountToWithdraw = when (event.click) {
            ClickType.LEFT -> 64
            ClickType.RIGHT -> 32
            ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT -> amount
            else -> 0
        }

        if (amountToWithdraw > 0) {
            val toWithdraw = minOf(amount, amountToWithdraw)
            if (toWithdraw > 0) {
                val withdrawnItem = ItemStack(material, toWithdraw)
                player.inventory.addItem(withdrawnItem)
                amount -= toWithdraw
                if (amount <= 0) {
                    toolInventory.remove(material)
                } else {
                    toolInventory[material] = amount
                }
                toolStorageModule.saveToolInventory(tool, toolInventory)
                open(player, tool) // Refresh GUI
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.view.title() == inventoryTitle) {
            val player = event.player as? Player ?: return
            // Remove from cooldown after a short delay
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                toolStorageModule.cooldowns.remove(player.uniqueId)
            }, 2L) // 2 ticks delay
        }
    }
}
