package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader

class ConfigManager(private val plugin: SurvivalPlus) {

    private val moduleConfigs = mutableMapOf<String, FileConfiguration>()
    lateinit var mainConfig: FileConfiguration
        private set

    fun setup() {
        plugin.saveDefaultConfig()
        mainConfig = plugin.config

        val configsDir = File(plugin.dataFolder, "configs")
        if (!configsDir.exists()) {
            configsDir.mkdirs()
        }
    }

    fun loadModuleConfig(moduleName: String) {
        val configFile = File(plugin.dataFolder, "configs/$moduleName.yml")
        if (!configFile.exists()) {
            plugin.saveResource("configs/$moduleName.yml", false)
        }

        val moduleConfig = YamlConfiguration.loadConfiguration(configFile)
        plugin.getResource("configs/$moduleName.yml")?.use { resourceStream ->
            val defaultConfig = YamlConfiguration.loadConfiguration(InputStreamReader(resourceStream))
            moduleConfig.setDefaults(defaultConfig)
        }
        moduleConfigs[moduleName] = moduleConfig
    }

    fun unloadModuleConfig(moduleName: String) {
        moduleConfigs.remove(moduleName)
    }

    fun getModuleConfig(moduleName: String): FileConfiguration? {
        return moduleConfigs[moduleName]
    }

    fun reloadMainConfig() {
        plugin.reloadConfig()
        mainConfig = plugin.config
    }
}
