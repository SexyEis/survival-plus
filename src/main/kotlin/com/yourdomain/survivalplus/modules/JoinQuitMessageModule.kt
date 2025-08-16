package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class JoinQuitMessageModule(private val plugin: SurvivalPlus) : Module, Listener {

    override fun getName(): String {
        return "join-quit-message"
    }

    override fun getDescription(): String {
        return "When this module is OFF, join and quit messages will be hidden."
    }

    override fun enable() {
        // This is the "ON" state for the module.
        // Per user request, "ON" means messages are SHOWN.
        // So we unregister the listener to restore default behavior.
        HandlerList.unregisterAll(this)
    }

    override fun disable() {
        // This is the "OFF" state for the module.
        // Per user request, "OFF" means messages are HIDDEN.
        // So we register the listener to suppress the messages.
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        event.joinMessage(null)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        event.quitMessage(null)
    }
}
