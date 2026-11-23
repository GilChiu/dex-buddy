package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client -> server: a social action ("feed", "post:<id>:<caption>", "like:<id>", "request:<uuid>", ...). */
data class SocialActionPayload(val action: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SocialActionPayload> = CustomPacketPayload.Type(DexBuddyMod.id("social_action"))
        val CODEC: StreamCodec<ByteBuf, SocialActionPayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(256), SocialActionPayload::action, ::SocialActionPayload)
    }
}

/** Server -> client: feed, friends or profile data (JSON with a "type"). */
data class SocialDataPayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SocialDataPayload> = CustomPacketPayload.Type(DexBuddyMod.id("social_data"))
        val CODEC: StreamCodec<ByteBuf, SocialDataPayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(262_144), SocialDataPayload::json, ::SocialDataPayload)
    }
}

/** Server -> client: something social happened (a request, a like, a new post) for the companion to say. */
data class SocialNoticePayload(val text: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SocialNoticePayload> = CustomPacketPayload.Type(DexBuddyMod.id("social_notice"))
        val CODEC: StreamCodec<ByteBuf, SocialNoticePayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(256), SocialNoticePayload::text, ::SocialNoticePayload)
    }
}
