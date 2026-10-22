package dev.aeronautics.ctrlz.network;

import dev.aeronautics.ctrlz.AeronauticsCtrlZMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * Пакет запроса от клиента к серверу на выполнение Undo или Redo.
 */
public record UndoRedoRequestPayload(
        boolean isRedo,
        boolean forceAdminOverride,
        Optional<String> targetPlayerUuid
) implements CustomPacketPayload {

    public static final Type<UndoRedoRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AeronauticsCtrlZMod.MOD_ID, "undo_redo_request")
    );

    public static final StreamCodec<ByteBuf, UndoRedoRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, UndoRedoRequestPayload::isRedo,
            ByteBufCodecs.BOOL, UndoRedoRequestPayload::forceAdminOverride,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), UndoRedoRequestPayload::targetPlayerUuid,
            UndoRedoRequestPayload::new
    );

    public Optional<UUID> parseTargetUuid() {
        return targetPlayerUuid.map(UUID::fromString);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
