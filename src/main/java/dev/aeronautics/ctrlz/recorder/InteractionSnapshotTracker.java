package dev.aeronautics.ctrlz.recorder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Краткоживущий буфер состояния блока/BE перед взаимодействием игрока ПКМ (для записи изменений кинетических параметров и посоха).
 */
public final class InteractionSnapshotTracker {
    public record PendingInteraction(
            ServerPlayer player,
            BlockPos pos,
            BlockState beforeState,
            @Nullable CompoundTag beforeNbt,
            ItemStack heldStack,
            long gameTime
    ) {}

    private static final Map<BlockPos, PendingInteraction> PENDING_BY_POS = new ConcurrentHashMap<>();

    private InteractionSnapshotTracker() {}

    public static void pushPreInteraction(
            ServerLevel level,
            ServerPlayer player,
            BlockPos pos,
            BlockState beforeState,
            @Nullable CompoundTag beforeNbt,
            ItemStack heldStack
    ) {
        PENDING_BY_POS.put(pos.immutable(), new PendingInteraction(
                player,
                pos.immutable(),
                beforeState,
                beforeNbt != null ? beforeNbt.copy() : null,
                heldStack.copy(),
                level.getGameTime()
        ));
    }

    @Nullable
    public static PendingInteraction pollMatching(ServerLevel level, BlockPos pos) {
        PendingInteraction pending = PENDING_BY_POS.remove(pos);
        if (pending == null) return null;
        if (level.getGameTime() - pending.gameTime() > 5L) {
            return null;
        }
        return pending;
    }
}
