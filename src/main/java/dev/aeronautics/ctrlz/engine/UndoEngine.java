package dev.aeronautics.ctrlz.engine;

import dev.aeronautics.ctrlz.AeronauticsCtrlZMod;
import dev.aeronautics.ctrlz.api.CtrlZEvents;
import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.contraption.ContraptionStateHandler;
import dev.aeronautics.ctrlz.history.HistoryManager;
import dev.aeronautics.ctrlz.history.HistoryStack;
import dev.aeronautics.ctrlz.model.Action;
import dev.aeronautics.ctrlz.model.BlockDelta;
import dev.aeronautics.ctrlz.model.CascadeEntry;
import dev.aeronautics.ctrlz.model.ScheduledTickRecord;
import dev.aeronautics.ctrlz.permission.PermissionHandler;
import dev.aeronautics.ctrlz.recorder.ActionRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Серверный атомарный движок отката и повтора (UndoEngine).
 *
 * Алгоритм транзакции:
 * 1. Проверка глобальных переключателей, кулдауна и прав (PermissionHandler).
 * 2. Очистка устаревших записей по TTL и проверка контрольной суммы (CRC32).
 * 3. Проверка загруженности целевого измерения и ВСЕХ затронутых чанков (включая каскады и контрапшены).
 * 4. Проверка конфликтов изменений (если после записи блок изменил другой игрок или физика) и чёрного списка BE.
 * 5. Генерация события BeforeUndoEvent / BeforeRedoEvent.
 * 6. Сбор pre-apply снимка всех затрагиваемых координат в журнал транзакции (RollbackJournal).
 * 7. Применение изменений в серверном потоке (каскады в обратном порядке для Undo, затем первичные дельты, затем контрапшен и scheduled ticks).
 * 8. Верификация применённого состояния; при любом сбое или исключении — мгновенный полный откат по RollbackJournal.
 * 9. Синхронизация чанков с клиентами и публикация AfterUndoEvent / AfterRedoEvent.
 */
public final class UndoEngine {
    private static final ThreadLocal<Boolean> PERFORMING_UNDO_REDO = ThreadLocal.withInitial(() -> false);

    private UndoEngine() {}

    public static boolean isPerformingUndoRedo() {
        return PERFORMING_UNDO_REDO.get();
    }

    private record JournalSnapshotEntry(
            BlockPos pos,
            BlockState stateBeforeTransaction,
            CompoundTag beNbtBeforeTransaction
    ) {}

    public static UndoResult executeUndo(ServerPlayer executor, UUID targetPlayerUuid, boolean forceAdminOverride) {
        if (!CtrlZConfig.SERVER.enableUndo.get()) {
            return logAndReject(executor, null, false, UndoRejectionReason.DISABLED, "Undo disabled in server config");
        }
        return executeInternal(executor, targetPlayerUuid, false, forceAdminOverride);
    }

    public static UndoResult executeRedo(ServerPlayer executor, UUID targetPlayerUuid, boolean forceAdminOverride) {
        if (!CtrlZConfig.SERVER.enableRedo.get()) {
            return logAndReject(executor, null, true, UndoRejectionReason.DISABLED, "Redo disabled in server config");
        }
        return executeInternal(executor, targetPlayerUuid, true, forceAdminOverride);
    }

    private static UndoResult executeInternal(
            ServerPlayer executor,
            UUID targetPlayerUuid,
            boolean isRedo,
            boolean forceAdminOverride
    ) {
        if (!PermissionHandler.canExecuteUndoRedo(executor, targetPlayerUuid)) {
            return logAndReject(executor, null, isRedo, UndoRejectionReason.NO_PERMISSION, "Insufficient permissions");
        }

        HistoryStack stack = HistoryManager.getOrCreateStack(targetPlayerUuid);
        long nowMs = System.currentTimeMillis();

        if (!stack.checkAndUpdateCooldown(nowMs, CtrlZConfig.SERVER.cooldownMs.get())) {
            return logAndReject(executor, null, isRedo, UndoRejectionReason.COOLDOWN, "Cooldown active");
        }

        stack.purgeExpired(nowMs, CtrlZConfig.SERVER.ttlMinutes.get());

        Optional<Action> candidateOpt = isRedo ? stack.popRedoForExecution() : stack.popUndoForExecution();
        if (candidateOpt.isEmpty()) {
            return logAndReject(executor, null, isRedo, UndoRejectionReason.HISTORY_EMPTY, isRedo ? "Redo stack empty" : "Undo stack empty");
        }

        Action action = candidateOpt.get();

        // Проверка прав конкретно на данное действие
        if (!PermissionHandler.canModifyAction(executor, action)) {
            restoreToStackOnFailure(stack, action, isRedo);
            return logAndReject(executor, action, isRedo, UndoRejectionReason.NO_PERMISSION, action.playerName());
        }

        // Проверка целостности контрольной суммы
        if (!action.verifyChecksum()) {
            restoreToStackOnFailure(stack, action, isRedo);
            return logAndReject(executor, action, isRedo, UndoRejectionReason.CHECKSUM_MISMATCH, action.id().toString());
        }

        MinecraftServer server = executor.getServer();
        if (server == null) {
            restoreToStackOnFailure(stack, action, isRedo);
            return logAndReject(executor, action, isRedo, UndoRejectionReason.CHUNKS_UNLOADED, "Server unavailable");
        }

        String dimId = action.dimension().location().toString();
        if (CtrlZConfig.isDimensionBlacklisted(dimId)) {
            restoreToStackOnFailure(stack, action, isRedo);
            return logAndReject(executor, action, isRedo, UndoRejectionReason.DIMENSION_BLACKLISTED, dimId);
        }

        ServerLevel targetLevel = server.getLevel(action.dimension());
        if (targetLevel == null) {
            restoreToStackOnFailure(stack, action, isRedo);
            return logAndReject(executor, action, isRedo, UndoRejectionReason.CHUNKS_UNLOADED, dimId);
        }

        // Проверка загруженности ВСЕХ чанков
        Set<ChunkPos> affectedChunks = action.getAffectedChunks();
        for (ChunkPos cp : affectedChunks) {
            if (!targetLevel.hasChunk(cp.x, cp.z)) {
                restoreToStackOnFailure(stack, action, isRedo);
                String chunkStr = cp.x + ", " + cp.z + " (" + dimId + ")";
                return logAndReject(executor, action, isRedo, UndoRejectionReason.CHUNKS_UNLOADED, chunkStr);
            }
        }

        // Проверка безопасности Redo для каскадов и конфликтов состояния мира
        boolean canBypassConflict = forceAdminOverride && PermissionHandler.isAdmin(executor);
        if (!canBypassConflict) {
            Optional<String> conflict = detectConflicts(targetLevel, action, isRedo);
            if (conflict.isPresent()) {
                restoreToStackOnFailure(stack, action, isRedo);
                UndoRejectionReason reason = (isRedo && !action.cascades().isEmpty())
                        ? UndoRejectionReason.UNSAFE_CASCADE_REDO
                        : UndoRejectionReason.CONFLICT_DETECTED;
                return logAndReject(executor, action, isRedo, reason, conflict.get());
            }
        }

        // Публикация отменяемого события BeforeUndo / BeforeRedo
        if (isRedo) {
            CtrlZEvents.BeforeRedoEvent preEvent = new CtrlZEvents.BeforeRedoEvent(executor, action);
            NeoForge.EVENT_BUS.post(preEvent);
            if (preEvent.isCanceled()) {
                restoreToStackOnFailure(stack, action, isRedo);
                return logAndReject(executor, action, true, UndoRejectionReason.EVENT_CANCELED, "Canceled by API listener");
            }
        } else {
            CtrlZEvents.BeforeUndoEvent preEvent = new CtrlZEvents.BeforeUndoEvent(executor, action);
            NeoForge.EVENT_BUS.post(preEvent);
            if (preEvent.isCanceled()) {
                restoreToStackOnFailure(stack, action, isRedo);
                return logAndReject(executor, action, false, UndoRejectionReason.EVENT_CANCELED, "Canceled by API listener");
            }
        }

        // Создаём журнал транзакции (RollbackJournal) для гарантии 100% атомарности
        List<JournalSnapshotEntry> rollbackJournal = captureTransactionJournal(targetLevel, action);

        PERFORMING_UNDO_REDO.set(true);
        try {
            if (isRedo) {
                applyRedoTransaction(targetLevel, action);
            } else {
                applyUndoTransaction(targetLevel, action);
            }

            if (!verifyPostConditions(targetLevel, action, isRedo)) {
                throw new IllegalStateException("Post-condition state verification failed after applying deltas.");
            }

            // Транзакция успешна — перекладываем в противоположный стек
            if (isRedo) {
                stack.commitRedoToUndo(action);
                NeoForge.EVENT_BUS.post(new CtrlZEvents.AfterRedoEvent(executor, action));
            } else {
                stack.commitUndoToRedo(action);
                NeoForge.EVENT_BUS.post(new CtrlZEvents.AfterUndoEvent(executor, action));
            }

            if (CtrlZConfig.SERVER.persistAcrossRestarts.get()) {
                dev.aeronautics.ctrlz.history.HistorySavedData.get(server).setDirty();
            }

            AeronauticsCtrlZMod.LOGGER.info(
                    "[CtrlZ] {} executed {} for action {} (type={}, player={}, dim={}, blocks={}, cascades={})",
                    executor.getGameProfile().getName(),
                    isRedo ? "REDO" : "UNDO",
                    action.id(),
                    action.type().getId(),
                    action.playerName(),
                    dimId,
                    action.primaryDeltas().size(),
                    action.cascades().size()
            );

            String okKey = isRedo ? "ctrlz.msg.redo_ok" : "ctrlz.msg.undo_ok";
            return UndoResult.ok(okKey, action.type().getId());

        } catch (Exception ex) {
            AeronauticsCtrlZMod.LOGGER.error(
                    "[CtrlZ] Transaction failed for action {} ({}). Executing atomic rollback...",
                    action.id(), ex.getMessage(), ex
            );
            rollbackFromJournal(targetLevel, rollbackJournal);
            restoreToStackOnFailure(stack, action, isRedo);
            return logAndReject(executor, action, isRedo, UndoRejectionReason.ATOMIC_ROLLBACK_ON_ERROR, ex.getMessage());
        } finally {
            PERFORMING_UNDO_REDO.set(false);
        }
    }

    private static void applyUndoTransaction(ServerLevel level, Action action) {
        // 1. Откатываем каскады в строго обратном порядке их возникновения
        List<CascadeEntry> reversedCascades = new ArrayList<>(action.cascades());
        Collections.reverse(reversedCascades);
        for (CascadeEntry ce : reversedCascades) {
            applyBlockDelta(level, ce.delta(), false);
        }

        // 2. Откатываем первичные дельты блоков в обратном порядке
        List<BlockDelta> reversedPrimary = new ArrayList<>(action.primaryDeltas());
        Collections.reverse(reversedPrimary);
        for (BlockDelta bd : reversedPrimary) {
            applyBlockDelta(level, bd, false);
        }

        // 3. Восстанавливаем контрапшен и кинетическую сеть
        if (action.contraptionSnapshot() != null && CtrlZConfig.SERVER.enabled.get()) {
            boolean ok = ContraptionStateHandler.restoreContraptionState(level, action.contraptionSnapshot(), false);
            if (!ok) {
                throw new IllegalStateException("Contraption restoration returned false");
            }
        }

        // 4. Восстанавливаем запланированные тики (scheduled ticks)
        if (CtrlZConfig.SERVER.restoreScheduledTicks.get()) {
            restoreScheduledTicks(level, action.scheduledTicks());
        }
    }

    private static void applyRedoTransaction(ServerLevel level, Action action) {
        // 1. Применяем первичные дельты в прямом порядке
        for (BlockDelta bd : action.primaryDeltas()) {
            applyBlockDelta(level, bd, true);
        }

        // 2. Применяем каскады в прямом хронологическом порядке
        for (CascadeEntry ce : action.cascades()) {
            applyBlockDelta(level, ce.delta(), true);
        }

        // 3. Применяем целевое состояние контрапшена
        if (action.contraptionSnapshot() != null && CtrlZConfig.SERVER.enabled.get()) {
            boolean ok = ContraptionStateHandler.restoreContraptionState(level, action.contraptionSnapshot(), true);
            if (!ok) {
                throw new IllegalStateException("Contraption redo returned false");
            }
        }
    }

    private static void applyBlockDelta(ServerLevel level, BlockDelta delta, boolean isRedo) {
        BlockPos pos = delta.pos();
        BlockState targetState = isRedo ? delta.afterState() : delta.beforeState();
        CompoundTag targetBeNbt = isRedo ? delta.afterBeNbt() : delta.beforeBeNbt();

        // Очищаем старый BlockEntity перед сменой блока во избежание выброса дропа или конфликта типов BE
        BlockEntity existingBe = level.getBlockEntity(pos);
        if (existingBe != null && (targetBeNbt == null || !targetState.is(level.getBlockState(pos).getBlock()))) {
            level.removeBlockEntity(pos);
        }

        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(pos, targetState, flags);

        if (targetBeNbt != null && !targetBeNbt.isEmpty()) {
            BlockEntity newBe = level.getBlockEntity(pos);
            if (newBe != null) {
                newBe.loadWithComponents(targetBeNbt, level.registryAccess());
                newBe.setChanged();
            }
        }
        level.sendBlockUpdated(pos, targetState, targetState, Block.UPDATE_ALL);
    }

    private static void restoreScheduledTicks(ServerLevel level, List<ScheduledTickRecord> ticks) {
        for (ScheduledTickRecord tr : ticks) {
            if (tr.isFluid()) {
                Fluid fluid = BuiltInRegistries.FLUID.get(tr.targetId());
                level.scheduleTick(tr.pos(), fluid, Math.max(1, tr.delayTicks()), tr.priority());
            } else {
                Block block = BuiltInRegistries.BLOCK.get(tr.targetId());
                level.scheduleTick(tr.pos(), block, Math.max(1, tr.delayTicks()), tr.priority());
            }
        }
    }

    private static Optional<String> detectConflicts(ServerLevel level, Action action, boolean isRedo) {
        for (BlockDelta bd : action.primaryDeltas()) {
            BlockEntity currentBe = level.getBlockEntity(bd.pos());
            if (CtrlZConfig.isBlockEntityBlacklisted(currentBe)) {
                return Optional.of("Blacklisted BlockEntity at " + bd.pos().toShortString());
            }
            BlockState expectedCurrent = isRedo ? bd.beforeState() : bd.afterState();
            BlockState actualCurrent = level.getBlockState(bd.pos());
            if (!statesCompatibleForUndo(expectedCurrent, actualCurrent)) {
                return Optional.of("Block modified at " + bd.pos().toShortString()
                        + " (expected " + expectedCurrent.getBlock().getName().getString()
                        + ", found " + actualCurrent.getBlock().getName().getString() + ")");
            }
        }

        if (isRedo && !action.cascades().isEmpty()) {
            for (CascadeEntry ce : action.cascades()) {
                BlockState expected = ce.delta().beforeState();
                BlockState actual = level.getBlockState(ce.delta().pos());
                if (!statesCompatibleForUndo(expected, actual)) {
                    return Optional.of("Cascade redo unsafe at " + ce.delta().pos().toShortString());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean statesCompatibleForUndo(BlockState expected, BlockState actual) {
        if (expected.equals(actual)) return true;
        if (expected.isAir() && actual.isAir()) return true;
        // Для текучих жидкостей и редстоун-проводов допускаем смену динамических свойств при совпадении базового блока
        return expected.getBlock() == actual.getBlock();
    }

    private static boolean verifyPostConditions(ServerLevel level, Action action, boolean isRedo) {
        for (BlockDelta bd : action.primaryDeltas()) {
            BlockState expectedTarget = isRedo ? bd.afterState() : bd.beforeState();
            BlockState actual = level.getBlockState(bd.pos());
            if (!statesCompatibleForUndo(expectedTarget, actual)) {
                return false;
            }
        }
        return true;
    }

    private static List<JournalSnapshotEntry> captureTransactionJournal(ServerLevel level, Action action) {
        Set<BlockPos> allPositions = action.getAllAffectedPositions();
        List<JournalSnapshotEntry> journal = new ArrayList<>(allPositions.size());
        for (BlockPos pos : allPositions) {
            BlockState st = level.getBlockState(pos);
            CompoundTag nbt = ActionRecorder.captureBlockEntityNbt(level, level.getBlockEntity(pos));
            journal.add(new JournalSnapshotEntry(pos, st, nbt));
        }
        return journal;
    }

    private static void rollbackFromJournal(ServerLevel level, List<JournalSnapshotEntry> journal) {
        for (JournalSnapshotEntry entry : journal) {
            try {
                level.removeBlockEntity(entry.pos());
                level.setBlock(entry.pos(), entry.stateBeforeTransaction(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                if (entry.beNbtBeforeTransaction() != null) {
                    BlockEntity be = level.getBlockEntity(entry.pos());
                    if (be != null) {
                        be.loadWithComponents(entry.beNbtBeforeTransaction(), level.registryAccess());
                        be.setChanged();
                    }
                }
                level.sendBlockUpdated(entry.pos(), entry.stateBeforeTransaction(), entry.stateBeforeTransaction(), Block.UPDATE_ALL);
            } catch (Exception e) {
                AeronauticsCtrlZMod.LOGGER.error("Critical error during journal rollback at {}: {}", entry.pos(), e.getMessage());
            }
        }
    }

    private static void restoreToStackOnFailure(HistoryStack stack, Action action, boolean isRedo) {
        if (isRedo) {
            stack.restoreFailedRedo(action);
        } else {
            stack.restoreFailedUndo(action);
        }
    }

    private static UndoResult logAndReject(
            ServerPlayer executor,
            Action action,
            boolean isRedo,
            UndoRejectionReason reason,
            String detail
    ) {
        if (CtrlZConfig.SERVER.debugLogging.get()) {
            AeronauticsCtrlZMod.LOGGER.info(
                    "[CtrlZ-Reject] player={}, op={}, action={}, reason={}, detail={}",
                    executor.getGameProfile().getName(),
                    isRedo ? "REDO" : "UNDO",
                    action != null ? action.id() : "none",
                    reason.name(),
                    detail
            );
        }
        NeoForge.EVENT_BUS.post(new CtrlZEvents.UndoRejectedEvent(executor, action, isRedo, reason, detail));
        return UndoResult.reject(reason, detail);
    }
}
