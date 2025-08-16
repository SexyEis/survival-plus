package com.yourdomain.survivalplus.modules

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class ToolStorageModule(private val plugin: SurvivalPlus) : Module, Listener {

    private val gson = Gson()
    private val storageKey = NamespacedKey(plugin, "tool_storage_inventory")

    override fun getName(): String {
        return "tool-storage"
    }

    override fun getDescription(): String {
        return "Gives tools their own inventory."
    }

    private lateinit var gui: ToolStorageGUI
    override fun enable() {
        gui = ToolStorageGUI(plugin, this)
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun disable() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun getToolInventory(tool: ItemStack): MutableMap<Material, Int> {
        val meta = tool.itemMeta ?: return mutableMapOf()
        val json = meta.persistentDataContainer.get(storageKey, PersistentDataType.STRING)
        return if (json != null) {
            val type = object : TypeToken<MutableMap<Material, Int>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableMapOf()
        }
    }

    fun saveToolInventory(tool: ItemStack, inventory: Map<Material, Int>) {
        val meta = tool.itemMeta ?: return
        val json = gson.toJson(inventory)
        meta.persistentDataContainer.set(storageKey, PersistentDataType.STRING, json)
        tool.itemMeta = meta
    }

    fun addToToolInventory(tool: ItemStack, item: ItemStack) {
        val inventory = getToolInventory(tool)
        inventory[item.type] = inventory.getOrDefault(item.type, 0) + item.amount
        saveToolInventory(tool, inventory)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!plugin.moduleManager.isModuleEnabled(getName())) return

        val player = event.player
        val tool = player.inventory.itemInMainHand
        if (tool.type == Material.AIR) return

        // This is a basic check for tools. You might want to expand this.
        if (!tool.type.toString().endsWith("_PICKAXE") && !tool.type.toString().endsWith("_AXE") && !tool.type.toString().endsWith("_SHOVEL") && !tool.type.toString().endsWith("_HOE")) {
            return
        }

        val drops = event.block.getDrops(tool)
        event.isDropItems = false // Prevent default drops

        for (drop in drops) {
            addToToolInventory(tool, drop)
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!plugin.moduleManager.isModuleEnabled(getName())) return
        if (event.action.isLeftClick) return

        val player = event.player
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) return

        // Basic tool check
        if (item.type.toString().endsWith("_PICKAXE") || item.type.toString().endsWith("_AXE") || item.type.toString().endsWith("_SHOVEL") || item.type.toString().endsWith("_HOE")) {
            if (player.isSneaking) return // To avoid conflict with Ultimine GUI

            event.isCancelled = true
            gui.open(player, item)
        }
    }
}
