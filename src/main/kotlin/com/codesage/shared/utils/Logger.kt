package com.codesage.shared.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 日志工具类
 */
object Logger {
    
    fun getLogger(clazz: Class<*>): org.slf4j.Logger {
        return LoggerFactory.getLogger(clazz)
    }
    
    inline fun <reified T> getLogger(): org.slf4j.Logger {
        return LoggerFactory.getLogger(T::class.java)
    }
    
    fun getLogger(name: String): org.slf4j.Logger {
        return LoggerFactory.getLogger(name)
    }
}