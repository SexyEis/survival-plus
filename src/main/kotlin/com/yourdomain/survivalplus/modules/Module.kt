package com.yourdomain.survivalplus.modules

interface Module {
    fun getName(): String
    fun getDescription(): String
    fun enable()
    fun disable()
}
