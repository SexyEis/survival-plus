package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus

class ConfigManager(private val plugin: SurvivalPlus) {

    fun setup() {
        plugin.config.options().copyDefaults(true)
        plugin.saveDefaultConfig()
    }

    fun reload() {
        plugin.reloadConfig()
    }

    fun getConfig() = plugin.config
}
