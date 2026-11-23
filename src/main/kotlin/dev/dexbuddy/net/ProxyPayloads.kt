package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Client -> server: one piece of an AI request the server sends on with its own key ("server key mode").
 * [route] is "GET /models", "POST /chat/completions" or "POST /audio/transcriptions"; bodies come in pieces
 * because client packets are limited to 32 KB.
 */
data class ProxyRequestPayload(val id: Int, val index: Int, val total: Int, val route: String, val contentType: String, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val CHUNK = 30_000
        val TYPE: CustomPacketPayload.Type<ProxyRequestPayload> = CustomPacketPayload.Type(DexBuddyMod.id("proxy_request"))
        val CODEC: StreamCodec<ByteBuf, ProxyRequestPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ProxyRequestPayload::id,
            ByteBufCodecs.VAR_INT, ProxyRequestPayload::index,
            ByteBufCodecs.VAR_INT, ProxyRequestPayload::total,
            ByteBufCodecs.stringUtf8(64), ProxyRequestPayload::route,
            ByteBufCodecs.stringUtf8(160), ProxyRequestPayload::contentType,
            ByteBufCodecs.byteArray(CHUNK), ProxyRequestPayload::data,
            ::ProxyRequestPayload,
        )
    }
}

/**
 * Server -> client: the provider's answer to a proxied request, streamed. [kind] is HEAD (status +
 * retry-after in [info]), DATA (bytes), END, or ERROR (a short reason in [info]).
 */
data class ProxyResponsePayload(val id: Int, val kind: Int, val status: Int, val info: String, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val HEAD = 0; const val DATA = 1; const val END = 2; const val ERROR = 3
        const val CHUNK = 60_000
        val TYPE: CustomPacketPayload.Type<ProxyResponsePayload> = CustomPacketPayload.Type(DexBuddyMod.id("proxy_response"))
        val CODEC: StreamCodec<ByteBuf, ProxyResponsePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ProxyResponsePayload::id,
            ByteBufCodecs.VAR_INT, ProxyResponsePayload::kind,
            ByteBufCodecs.VAR_INT, ProxyResponsePayload::status,
            ByteBufCodecs.stringUtf8(256), ProxyResponsePayload::info,
            ByteBufCodecs.byteArray(CHUNK + 16), ProxyResponsePayload::data,
            ::ProxyResponsePayload,
        )
    }
}

/** Server -> client on join: what this server offers (for now: whether it lends its AI key). */
data class ServerFeaturesPayload(val serverKey: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ServerFeaturesPayload> = CustomPacketPayload.Type(DexBuddyMod.id("server_features"))
        val CODEC: StreamCodec<ByteBuf, ServerFeaturesPayload> =
            StreamCodec.composite(ByteBufCodecs.BOOL, ServerFeaturesPayload::serverKey, ::ServerFeaturesPayload)
    }
}
