package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Client -> server: one piece of a photo (JPEG) being saved to the Trainer's gallery. Photos are sent
 * in pieces because a client packet can't be bigger than 32 KB.
 */
data class PhotoChunkPayload(val id: String, val index: Int, val total: Int, val mode: String, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val CHUNK = 30_000
        val TYPE: CustomPacketPayload.Type<PhotoChunkPayload> = CustomPacketPayload.Type(DexBuddyMod.id("photo_chunk"))
        val CODEC: StreamCodec<ByteBuf, PhotoChunkPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(40), PhotoChunkPayload::id,
            ByteBufCodecs.VAR_INT, PhotoChunkPayload::index,
            ByteBufCodecs.VAR_INT, PhotoChunkPayload::total,
            ByteBufCodecs.stringUtf8(16), PhotoChunkPayload::mode,
            ByteBufCodecs.byteArray(CHUNK), PhotoChunkPayload::data,
            ::PhotoChunkPayload,
        )
    }
}

/**
 * Server -> client: one piece of a video clip ([total] pieces; 0 means "that photo isn't a clip").
 * The pieces join into the clip file: "DXC1", fps, frame count, then each JPEG frame with its length.
 */
data class ClipDataPayload(val id: String, val index: Int, val total: Int, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val CHUNK = 250_000
        val TYPE: CustomPacketPayload.Type<ClipDataPayload> = CustomPacketPayload.Type(DexBuddyMod.id("clip_data"))
        val CODEC: StreamCodec<ByteBuf, ClipDataPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(40), ClipDataPayload::id,
            ByteBufCodecs.VAR_INT, ClipDataPayload::index,
            ByteBufCodecs.VAR_INT, ClipDataPayload::total,
            ByteBufCodecs.byteArray(CHUNK), ClipDataPayload::data,
            ::ClipDataPayload,
        )
    }
}

/** Server -> client: a photo was saved ([count] in the gallery now), or why it wasn't ([error]). */
data class PhotoSavedPayload(val id: String, val count: Int, val error: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhotoSavedPayload> = CustomPacketPayload.Type(DexBuddyMod.id("photo_saved"))
        val CODEC: StreamCodec<ByteBuf, PhotoSavedPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(40), PhotoSavedPayload::id,
            ByteBufCodecs.VAR_INT, PhotoSavedPayload::count,
            ByteBufCodecs.stringUtf8(256), PhotoSavedPayload::error,
            ::PhotoSavedPayload,
        )
    }
}
