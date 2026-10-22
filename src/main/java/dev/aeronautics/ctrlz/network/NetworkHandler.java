package dev.aeronautics.ctrlz.network;

import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.engine.UndoResult;
import dev.aeronautics.ctrlz.history.HistoryManager;
import dev.aeronautics.ctrlz.history.HistoryStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * Регистрация пакетов NeoForge 1.21.1 PayloadRegistrar и серверная обработка.
 */
public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";

    private NetworkHandler() {}

    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(
                UndoRedoRequestPayload.TYPE,
                UndoRedoRequestPayload.STREAM_CODEC,
                NetworkHandler::handleServerRequest
        );

        registrar.playToClient(
                UndoRedoResultPayload.TYPE,
                UndoRedoResultPayload.STREAM_CODEC,
                NetworkHandler::handleClientResponse
        );
    }

    private static void handleServerRequest(final UndoRedoRequestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            UUID targetUuid = payload.parseTargetUuid().orElse(serverPlayer.getUUID());
            UndoResult result = payload.isRedo()
                    ? UndoEngine.executeRedo(serverPlayer, targetUuid, payload.forceAdminOverride())
                    : UndoEngine.executeUndo(serverPlayer, targetUuid, payload.forceAdminOverride());

            HistoryStack stack = HistoryManager.getOrCreateStack(targetUuid);
            PacketDistributor.sendToPlayer(serverPlayer, new UndoRedoResultPayload(
                    result.success(),
                    payload.isRedo(),
                    result.translationKey(),
                    result.detail(),
                    stack.getUndoCount(),
                    stack.getRedoCount()
            ));
        });
    }

    private static void handleClientResponse(final UndoRedoResultPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;

            ChatFormatting color = payload.success() ? ChatFormatting.GREEN : ChatFormatting.RED;
            Component msg = Component.translatable(payload.translationKey(), payload.detailMessage())
                    .withStyle(color)
                    .append(Component.literal(String.format(" [Undo: %d | Redo: %d]", payload.remainingUndo(), payload.remainingRedo()))
                            .withStyle(ChatFormatting.GRAY));
            player.displayClientMessage(msg, true);
        });
    }
}
