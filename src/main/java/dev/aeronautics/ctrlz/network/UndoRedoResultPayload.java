package dev.aeronautics.ctrlz.network;

import dev.aeronautics.ctrlz.AeronauticsCtrlZMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Ответ сервера клиенту о результате выполнения операции Undo/Redo и актуальном состоянии стеков.
 */
public record UndoRedoResultPayload(
        boolean success,
        boolean isRedo,
        String translationKey,
        String detailMessage,
        int remainingUndo,
        int remainingRedo
) implements CustomPacketPayload {

    public static final Type<UndoRedoResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AeronauticsCtrlZMod.MOD_ID, "undo_redo_result")
    );

    public static final StreamCodec<ByteBuf, UndoRedoResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, UndoRedoResultPayload::success,
            ByteBufCodecs.BOOL, UndoRedoResultPayload::isRedo,
            ByteBufCodecs.STRING_UTF8, UndoRedoResultPayload::translationKey,
            ByteBufCodecs.STRING_UTF8, UndoRedoResultPayload::detailMessage,
            ByteBufCodecs.VAR_INT, UndoRedoResultPayload::remainingUndo,
            ByteBufCodecs.VAR_INT, UndoRedoResultPayload::remainingRedo,
            UndoRedoResultPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
