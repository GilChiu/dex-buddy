package dev.dexbuddy.net

import dev.dexbuddy.DexBuddyMod
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client -> server: a voice command for the Trainer's companion ("stay", "glow_on", "hide", ...). */
data class CompanionCommandPayload(val command: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CompanionCommandPayload> = CustomPacketPayload.Type(DexBuddyMod.id("companion_command"))
        val CODEC: StreamCodec<ByteBuf, CompanionCommandPayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(32), CompanionCommandPayload::command, ::CompanionCommandPayload)
    }
}

/** Client -> server: quiz actions ("start:who", "start:type", "answer:<text>", "hint", "stop"). */
data class QuizActionPayload(val action: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<QuizActionPayload> = CustomPacketPayload.Type(DexBuddyMod.id("quiz_action"))
        val CODEC: StreamCodec<ByteBuf, QuizActionPayload> =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(256), QuizActionPayload::action, ::QuizActionPayload)
    }
}

/**
 * Server -> client: what the companion should say about the quiz, whether a question is open, and the
 * species to draw ([species] id, "" for none): as a silhouette while open, in color when [reveal].
 */
data class QuizLinePayload(val line: String, val open: Boolean, val species: String, val reveal: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<QuizLinePayload> = CustomPacketPayload.Type(DexBuddyMod.id("quiz_line"))
        val CODEC: StreamCodec<ByteBuf, QuizLinePayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(2048), QuizLinePayload::line,
            ByteBufCodecs.BOOL, QuizLinePayload::open,
            ByteBufCodecs.stringUtf8(128), QuizLinePayload::species,
            ByteBufCodecs.BOOL, QuizLinePayload::reveal,
            ::QuizLinePayload,
        )
    }
}

/** Server -> client: the companion found a gift (item description id + count) while charged. */
data class GiftPayload(val itemKey: String, val count: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<GiftPayload> = CustomPacketPayload.Type(DexBuddyMod.id("gift"))
        val CODEC: StreamCodec<ByteBuf, GiftPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256), GiftPayload::itemKey,
            ByteBufCodecs.VAR_INT, GiftPayload::count,
            ::GiftPayload,
        )
    }
}
