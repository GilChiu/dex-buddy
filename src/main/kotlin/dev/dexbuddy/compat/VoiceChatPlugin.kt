package dev.dexbuddy.compat

import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.events.ClientSoundEvent
import de.maxhenkel.voicechat.api.events.EventRegistration
import dev.dexbuddy.VoiceState

/**
 * Loaded only when Simple Voice Chat is installed (its "voicechat" entrypoint). While you hold the
 * Dex Buddy key, your outgoing voice-chat audio is replaced with silence.
 */
class VoiceChatPlugin : VoicechatPlugin {
    override fun getPluginId(): String = "dexbuddy"

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(ClientSoundEvent::class.java) { event ->
            if (VoiceState.shouldSilenceMic()) {
                event.setRawAudio(ShortArray(event.rawAudio.size))
            }
        }
    }
}
