package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Client -> server: what this Trainer's Dex is playing (JSON). {"op":"now","id","title","author","dur","pos","playing","share"},
 * {"op":"pause","pos"}, {"op":"resume","pos"}, {"op":"stop"}. The song itself is never sent: each player's
 * game streams it.
 */
data class MusicActionPayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MusicActionPayload> = CustomPacketPayload.Type(DexBuddyMod.id("music_action"))
        val CODEC: StreamCodec<ByteBuf, MusicActionPayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(2048), MusicActionPayload::json, ::MusicActionPayload)
    }
}

/**
 * Songs other players can make your game stream: YouTube and SoundCloud pages only. Anything else (any web
 * address) could be used to learn the IP address of everyone nearby, so it's refused on both sides.
 */
object MusicIds {
    private val ALLOWED = Regex(
        "^https://(?:(?:www\\.|m\\.|music\\.)?youtube\\.com/watch\\?v=[\\w-]{6,20}(?:&[\\w=&%-]*)?|youtu\\.be/[\\w-]{6,20}|(?:www\\.|m\\.)?soundcloud\\.com/[\\w-]+/[\\w-]+(?:\\?[\\w=&%-]*)?)$",
        RegexOption.IGNORE_CASE,
    )

    fun allowed(id: String): Boolean = id.length <= 300 && ALLOWED.matches(id)

    /** Song titles shown to other players: no formatting codes or control characters. */
    fun clean(s: String, max: Int): String = s.replace('§', ' ').replace(Regex("\\p{Cntrl}"), " ").trim().take(max)
}

/**
 * Server -> client: a Dex nearby is playing something (or stopped). JSON with the owner, the entity the sound
 * comes from (their floating Dex, or the player), the song id/title, and where it is in the song right now.
 */
data class MusicStatePayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MusicStatePayload> = CustomPacketPayload.Type(DexBuddyMod.id("music_state"))
        val CODEC: StreamCodec<ByteBuf, MusicStatePayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(4096), MusicStatePayload::json, ::MusicStatePayload)
    }
}
