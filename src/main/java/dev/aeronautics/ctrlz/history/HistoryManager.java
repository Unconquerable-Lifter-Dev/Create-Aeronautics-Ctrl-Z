package dev.aeronautics.ctrlz.history;

import dev.aeronautics.ctrlz.AeronauticsCtrlZMod;
import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.model.Action;
import dev.aeronautics.ctrlz.recorder.CascadeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Управляет стеками истории всех игроков, персистентностью и жизненным циклом игрока
 * (смерть, смена измерения, телепортация, выход из игры).
 */
public final class HistoryManager {
    private static final Map<UUID, HistoryStack> MEMORY_STACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Action> LAST_OPEN_ACTION = new ConcurrentHashMap<>();

    private HistoryManager() {}

    public static HistoryStack getOrCreateStack(UUID playerUuid) {
        return MEMORY_STACKS.computeIfAbsent(playerUuid, HistoryStack::new);
    }

    public static boolean recordAction(ServerPlayer player, Action action) {
        String dimId = action.dimension().location().toString();
        if (CtrlZConfig.isDimensionBlacklisted(dimId)) {
            return false;
        }
        HistoryStack stack = getOrCreateStack(player.getUUID());
        stack.purgeExpired(System.currentTimeMillis(), CtrlZConfig.SERVER.ttlMinutes.get());
        boolean recorded = stack.pushAction(
                action,
                CtrlZConfig.SERVER.maxActions.get(),
                CtrlZConfig.SERVER.maxSnapshotSizeKb.get()
        );
        if (recorded) {
            LAST_OPEN_ACTION.put(player.getUUID(), action);
            if (CtrlZConfig.SERVER.persistAcrossRestarts.get() && player.getServer() != null) {
                HistorySavedData saved = HistorySavedData.get(player.getServer());
                saved.getStacks().put(player.getUUID(), stack);
                saved.setDirty();
            }
        } else {
            AeronauticsCtrlZMod.LOGGER.warn(
                    "Action {} by player {} exceeded maxSnapshotSizeKb ({} KB) and was discarded.",
                    action.id(), player.getGameProfile().getName(), CtrlZConfig.SERVER.maxSnapshotSizeKb.get()
            );
        }
        return recorded;
    }

    public static Optional<Action> getOpenActionForCascade(UUID playerUuid) {
        return Optional.ofNullable(LAST_OPEN_ACTION.get(playerUuid));
    }

    public static void clearOpenActionContext(UUID playerUuid) {
        LAST_OPEN_ACTION.remove(playerUuid);
        CascadeTracker.clearPlayerContexts(playerUuid);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MEMORY_STACKS.clear();
        LAST_OPEN_ACTION.clear();
        if (CtrlZConfig.SERVER.persistAcrossRestarts.get()) {
            HistorySavedData saved = HistorySavedData.get(event.getServer());
            MEMORY_STACKS.putAll(saved.getStacks());
            AeronauticsCtrlZMod.LOGGER.info("Loaded {} persistent undo/redo stacks.", MEMORY_STACKS.size());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (CtrlZConfig.SERVER.persistAcrossRestarts.get()) {
            HistorySavedData saved = HistorySavedData.get(event.getServer());
            saved.getStacks().clear();
            saved.getStacks().putAll(MEMORY_STACKS);
            saved.setDirty();
        }
        LAST_OPEN_ACTION.clear();
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // При смерти закрываем активные окна записи каскадов, сохраняя стек истории консистентным
            clearOpenActionContext(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearOpenActionContext(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearOpenActionContext(player.getUUID());
            if (!CtrlZConfig.SERVER.persistAcrossRestarts.get()) {
                // В P0 при persistAcrossRestarts=false стек живёт в памяти сессии сервера,
                // а при необходимости синхронизируется с SavedData при включённой опции
            } else if (player.getServer() != null) {
                HistorySavedData saved = HistorySavedData.get(player.getServer());
                saved.getStacks().put(player.getUUID(), getOrCreateStack(player.getUUID()));
                saved.setDirty();
            }
        }
    }
}
