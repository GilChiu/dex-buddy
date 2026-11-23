package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client -> server: gallery actions ("list", "get:<id>", "delete:<id>"). */
data class GalleryRequestPayload(val action: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<GalleryRequestPayload> = CustomPacketPayload.Type(DexBuddyMod.id("gallery_request"))
        val CODEC: StreamCodec<ByteBuf, GalleryRequestPayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(64), GalleryRequestPayload::action, ::GalleryRequestPayload)
    }
}

/** Server -> client: the Trainer's gallery index (JSON array of {id, time, mode, dim, x, y, z}). */
data class GalleryListPayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<GalleryListPayload> = CustomPacketPayload.Type(DexBuddyMod.id("gallery_list"))
        val CODEC: StreamCodec<ByteBuf, GalleryListPayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(262_144), GalleryListPayload::json, ::GalleryListPayload)
    }
}

/** Server -> client: one photo's JPEG bytes. */
data class PhotoDataPayload(val id: String, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhotoDataPayload> = CustomPacketPayload.Type(DexBuddyMod.id("photo_data"))
        val CODEC: StreamCodec<ByteBuf, PhotoDataPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(40), PhotoDataPayload::id,
            ByteBufCodecs.byteArray(700_000), PhotoDataPayload::data,
            ::PhotoDataPayload,
        )
    }
}
