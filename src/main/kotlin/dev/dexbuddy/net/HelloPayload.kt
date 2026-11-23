package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Empty marker channel. If the client can send it, the server has Dex Buddy installed. */
object HelloPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<HelloPayload> = CustomPacketPayload.Type(DexBuddyMod.id("hello"))
    val CODEC: StreamCodec<ByteBuf, HelloPayload> = StreamCodec.unit(HelloPayload)

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
