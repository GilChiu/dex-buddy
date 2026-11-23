package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client asks: what spawns here (query blank), or where does a species spawn (query = its name). */
data class SpawnQueryPayload(val requestId: Int, val query: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SpawnQueryPayload> = CustomPacketPayload.Type(DexBuddyMod.id("spawn_query"))
        val CODEC: StreamCodec<ByteBuf, SpawnQueryPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpawnQueryPayload::requestId,
            ByteBufCodecs.stringUtf8(64), SpawnQueryPayload::query,
            ::SpawnQueryPayload,
        )
    }
}

/** Server answers in plain English, ready for the model. */
data class SpawnAnswerPayload(val requestId: Int, val text: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SpawnAnswerPayload> = CustomPacketPayload.Type(DexBuddyMod.id("spawn_answer"))
        val CODEC: StreamCodec<ByteBuf, SpawnAnswerPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpawnAnswerPayload::requestId,
            ByteBufCodecs.stringUtf8(16384), SpawnAnswerPayload::text,
            ::SpawnAnswerPayload,
        )
    }
}
