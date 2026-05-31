package com.codesage.shared.config

import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jdom.output.XMLOutputter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PluginConfigSerializationTest {

    @Test
    fun `ProviderConfig should serialize and deserialize correctly`() {
        val config = ProviderConfig().apply {
            id = "test-id-123"
            name = "MiniMax"
            providerType = ProviderTypes.MINIMAX
            baseUrl = "https://api.minimaxi.com"
            models = mutableListOf("MiniMax-M2", "MiniMax-M2.5")
            isEnabled = true
        }

        val element = XmlSerializer.serialize(config)
        println("Serialized ProviderConfig XML:\n${XMLOutputter().outputString(element)}")

        val deserialized = XmlSerializer.deserialize(element, ProviderConfig::class.java)
        assertEquals("test-id-123", deserialized.id)
        assertEquals("MiniMax", deserialized.name)
        assertEquals("minimax", deserialized.providerType)
        assertEquals("https://api.minimaxi.com", deserialized.baseUrl)
        assertEquals(listOf("MiniMax-M2", "MiniMax-M2.5"), deserialized.models)
        assertTrue(deserialized.isEnabled)
    }

    @Test
    fun `PluginConfigState with providers should serialize and deserialize correctly`() {
        val state = PluginConfigState().apply {
            providers.add(ProviderConfig().apply {
                id = "791b686c-f3fa-41e4-be16-548a65ca7c0a"
                name = "MiniMax"
                providerType = ProviderTypes.MINIMAX
                baseUrl = "https://api.minimaxi.com"
                models = mutableListOf("MiniMax-M2", "MiniMax-M2.5")
                isEnabled = true
            })
            defaultProviderId = "791b686c-f3fa-41e4-be16-548a65ca7c0a"
            defaultModel = "MiniMax-M2"
        }

        val element = XmlSerializer.serialize(state)
        val xmlString = XMLOutputter().outputString(element)
        println("Serialized PluginConfigState XML:\n$xmlString")

        val deserialized = XmlSerializer.deserialize(element, PluginConfigState::class.java)
        println("Deserialized providers count: ${deserialized.providers.size}")
        println("Deserialized provider id: ${deserialized.providers.firstOrNull()?.id}")
        println("Deserialized provider isEnabled: ${deserialized.providers.firstOrNull()?.isEnabled}")

        assertEquals(1, deserialized.providers.size)
        assertEquals("791b686c-f3fa-41e4-be16-548a65ca7c0a", deserialized.providers[0].id)
        assertEquals("MiniMax", deserialized.providers[0].name)
        assertTrue(deserialized.providers[0].isEnabled)
        assertEquals("791b686c-f3fa-41e4-be16-548a65ca7c0a", deserialized.defaultProviderId)
    }

    @Test
    fun `copyBean should copy PluginConfigState with providers`() {
        val source = PluginConfigState().apply {
            providers.add(ProviderConfig().apply {
                id = "test-id"
                name = "Test"
                providerType = ProviderTypes.MINIMAX
                baseUrl = "https://api.minimaxi.com"
                models = mutableListOf("model1")
                isEnabled = true
            })
        }

        val target = PluginConfigState()
        XmlSerializerUtil.copyBean(source, target)

        println("After copyBean, target.providers.size = ${target.providers.size}")
        println("After copyBean, target.providers[0].id = ${target.providers.firstOrNull()?.id}")

        assertEquals(1, target.providers.size)
        assertEquals("test-id", target.providers[0].id)
    }
}
