package com.github.b3kt.arthasbridge.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

@Service(Service.Level.APP)
@State(name = "ArthasBridgeSettings", storages = [Storage("arthas-bridge.xml")])
class ArthasSettings : SimplePersistentStateComponent<ArthasSettings.Options>(Options()) {

    class Options : BaseState() {
        /** Empty = auto-detect (~/.arthas/lib, previously downloaded jar). */
        var arthasBootPath by string("")
        var httpPort by property(8563)
        /** 0 = don't open a telnet port; the plugin only needs HTTP. */
        var telnetPort by property(0)
        var expandDepth by property(2)
        var resultLimit by property(5)
        var maskSensitiveData by property(true)
        /** true = plain-language summaries (watch/tt/trace/stack/monitor); false = Arthas terminal format. */
        var readableOutput by property(true)
        /** false = follow the IDE console font size. */
        var customFontSize by property(false)
        var fontSize by property(13)
        var allowRemote by property(false)
        var stopOnProjectClose by property(true)
        var username by string("")
    }

    var password: String?
        get() = PasswordSafe.instance.getPassword(CREDENTIALS)
        set(value) = PasswordSafe.instance.setPassword(CREDENTIALS, value?.ifEmpty { null })

    companion object {
        private val CREDENTIALS = CredentialAttributes(generateServiceName("Arthas Bridge", "arthas"))

        /** Published when the Settings page is applied, so open tool windows pick up the change. */
        @Topic.AppLevel
        val CHANGED = Topic(Runnable::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(): ArthasSettings = service()
    }
}
