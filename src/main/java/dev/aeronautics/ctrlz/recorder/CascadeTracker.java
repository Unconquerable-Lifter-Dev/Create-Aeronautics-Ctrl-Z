package dev.aeronautics.ctrlz.recorder;

import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.history.HistoryManager;
import dev.aeronautics.ctrlz.model.Action;
import dev.aeronautics.ctrlz.model.ActionType;
import dev.aeronautics.ctrlz.model.BlockDelta;
import dev.aeronautics.ctrlz.model.CascadeCause;
import dev.aeronautics.ctrlz.model.CascadeEntry;
import dev.aeronautics.ctrlz.model.ScheduledTickRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Отслеживает и атрибутирует каскадные изменения (взрывы TNT, движение поршней, потоки воды и лавы)
 * к конкретному игроку и родительскому Action ID.
 * Неатрибутированные разрушения (криперы, природный огонь) по умолчанию игнорируются.
 */
public final class CascadeTracker {
    public record AttributionContext(
            UUID playerUuid,
            UUID actionId,
            BlockPos originPos,
            long createdGameTime,
            AtomicInteger sequenceCounter
    ) {
        public int nextSequence() {
            return sequenceCounter.incrementAndGet();
        }
    }

    private static final Map<UUID, AttributionContext> ACTIVE_PLAYER_CONTEXT = new ConcurrentHashMap<>();
    private static final Map<BlockPos, AttributionContext> FLUID_ORIGIN_ATTRIBUTION = new ConcurrentHashMap<>();
    private static final Map<BlockPos, AttributionContext> TNT_BLOCK_ATTRIBUTION = new ConcurrentHashMap<>();

    private CascadeTracker() {}

    public static void openPlayerActionContext(ServerLevel level, UUID playerUuid, BlockPos originPos, UUID actionId) {
        AttributionContext ctx = new AttributionContext(
                playerUuid, actionId, originPos.immutable(), level.getGameTime(), new AtomicInteger(0)
        );
        ACTIVE_PLAYER_CONTEXT.put(playerUuid, ctx);
    }

    public static void attributeTntPlacement(ServerLevel level, BlockPos pos, UUID playerUuid, UUID actionId) {
        TNT_BLOCK_ATTRIBUTION.put(pos.immutable(), new AttributionContext(
                playerUuid, actionId, pos.immutable(), level.getGameTime(), new AtomicInteger(0)
        ));
    }

    public static void recordPreTntIgnition(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState beforeState) {
        if (!CtrlZConfig.SERVER.recordExplosions.get()) return;
        BlockDelta igniteDelta = new BlockDelta(pos, beforeState, Blocks.AIR.defaultBlockState(), null, null);
        Action action = new Action(
                UUID.randomUUID(),
                player.getUUID(),
                player.getGameProfile().getName(),
                ActionType.CASCADE_COMPOSITE,
                level.dimension(),
                List.of(igniteDelta),
                new ArrayList<>(),
                null,
                new ArrayList<>(),
                System.currentTimeMillis()
        );
        if (HistoryManager.recordAction(player, action)) {
            attributeTntPlacement(level, pos, player.getUUID(), action.id());
            openPlayerActionContext(level, player.getUUID(), pos, action.id());
        }
    }

    public static void recordPreFluidBucketUse(ServerLevel level, ServerPlayer player, BlockPos targetPos) {
        if (!CtrlZConfig.SERVER.recordFluids.get()) return;
        BlockState beforeState = level.getBlockState(targetPos);
        CompoundTag beforeBe = ActionRecorder.captureBlockEntityNbt(level, level.getBlockEntity(targetPos));
        BlockDelta delta = new BlockDelta(targetPos, beforeState, beforeState, beforeBe, null);

        Action action = new Action(
                UUID.randomUUID(),
                player.getUUID(),
                player.getGameProfile().getName(),
                ActionType.BLOCK_PLACE,
                level.dimension(),
                List.of(delta),
                new ArrayList<>(),
                null,
                new ArrayList<>(),
                System.currentTimeMillis()
        );
        if (HistoryManager.recordAction(player, action)) {
            AttributionContext ctx = new AttributionContext(
                    player.getUUID(), action.id(), targetPos.immutable(), level.getGameTime(), new AtomicInteger(0)
            );
            FLUID_ORIGIN_ATTRIBUTION.put(targetPos.immutable(), ctx);
            ACTIVE_PLAYER_CONTEXT.put(player.getUUID(), ctx);
        }
    }

    /**
     * Обработка взрыва ДО разрушения блоков — сохраняет полные дельты всех затрагиваемых взрывом блоков и их BE NBT.
     */
    @SubscribeEvent
    public static void onExplosionDetonate(final ExplosionEvent.Detonate event) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!CtrlZConfig.SERVER.recordExplosions.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Explosion explosion = event.getExplosion();
        AttributionContext ctx = resolveExplosionAttribution(level, explosion);
        if (ctx == null) {
            return; // Неатрибутированный взрыв (например, крипер при recordUnattributed = false)
        }

        Optional<Action> parentOpt = findActionById(ctx.playerUuid(), ctx.actionId());
        if (parentOpt.isEmpty()) return;
        Action parentAction = parentOpt.get();

        for (BlockPos affectedPos : event.getAffectedBlocks()) {
            BlockState beforeState = level.getBlockState(affectedPos);
            if (beforeState.isAir()) continue;
            BlockEntity be = level.getBlockEntity(affectedPos);
            if (CtrlZConfig.isBlockEntityBlacklisted(be)) continue;

            CompoundTag beforeBeNbt = ActionRecorder.captureBlockEntityNbt(level, be);
            BlockDelta delta = new BlockDelta(
                    affectedPos.immutable(),
                    beforeState,
                    Blocks.AIR.defaultBlockState(),
                    beforeBeNbt,
                    null
            );
            CascadeEntry entry = new CascadeEntry(
                    delta,
                    CascadeCause.TNT_EXPLOSION,
                    parentAction.id(),
                    ctx.nextSequence()
            );
            parentAction.appendCascade(entry);
        }
    }

    /**
     * Захват состояния структуры перед срабатыванием поршня, активированного действием игрока.
     */
    @SubscribeEvent
    public static void onPistonPre(final PistonEvent.Pre event) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!CtrlZConfig.SERVER.recordPistons.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pistonPos = event.getPos().immutable();
        AttributionContext ctx = findNearestRecentPlayerContext(level, pistonPos, 16.0, 40L);
        if (ctx == null) return;

        Optional<Action> parentOpt = findActionById(ctx.playerUuid(), ctx.actionId());
        if (parentOpt.isEmpty()) return;
        Action parentAction = parentOpt.get();

        var structureHelper = event.getStructureHelper();
        if (structureHelper == null || !structureHelper.resolve()) return;

        for (BlockPos movedPos : structureHelper.getToPush()) {
            recordPistonBlockPreState(level, movedPos, event.getDirection(), parentAction, ctx);
        }
        for (BlockPos destroyedPos : structureHelper.getToDestroy()) {
            BlockState before = level.getBlockState(destroyedPos);
            CompoundTag beforeBe = ActionRecorder.captureBlockEntityNbt(level, level.getBlockEntity(destroyedPos));
            BlockDelta delta = new BlockDelta(destroyedPos, before, Blocks.AIR.defaultBlockState(), beforeBe, null);
            parentAction.appendCascade(new CascadeEntry(delta, CascadeCause.PISTON_PUSH_PULL, parentAction.id(), ctx.nextSequence()));
        }
    }

    private static void recordPistonBlockPreState(
            ServerLevel level,
            BlockPos movedPos,
            net.minecraft.core.Direction moveDir,
            Action parentAction,
            AttributionContext ctx
    ) {
        BlockState beforeAtOrigin = level.getBlockState(movedPos);
        CompoundTag beforeBeOrigin = ActionRecorder.captureBlockEntityNbt(level, level.getBlockEntity(movedPos));
        BlockPos destPos = movedPos.relative(moveDir);
        BlockState beforeAtDest = level.getBlockState(destPos);
        CompoundTag beforeBeDest = ActionRecorder.captureBlockEntityNbt(level, level.getBlockEntity(destPos));

        BlockDelta originDelta = new BlockDelta(movedPos, beforeAtOrigin, Blocks.AIR.defaultBlockState(), beforeBeOrigin, null);
        BlockDelta destDelta = new BlockDelta(destPos, beforeAtDest, beforeAtOrigin, beforeBeDest, beforeBeOrigin);

        parentAction.appendCascade(new CascadeEntry(originDelta, CascadeCause.PISTON_PUSH_PULL, parentAction.id(), ctx.nextSequence()));
        parentAction.appendCascade(new CascadeEntry(destDelta, CascadeCause.PISTON_PUSH_PULL, parentAction.id(), ctx.nextSequence()));
    }

    /**
     * Вызывается из FlowingFluidMixin и ServerLevelMixin при растекании атрибутированной жидкости,
     * смыве блоков или генерации булыжника/обсидиана.
     */
    public static void onFluidSpreadOrBlockChange(
            ServerLevel level,
            BlockPos fromPos,
            BlockPos toPos,
            BlockState beforeState,
            BlockState afterState,
            @Nullable CompoundTag beforeBeNbt
    ) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!CtrlZConfig.SERVER.recordFluids.get()) return;

        AttributionContext ctx = FLUID_ORIGIN_ATTRIBUTION.get(fromPos);
        if (ctx == null) {
            ctx = findNearestFluidContext(level, fromPos, 10.0, 200L);
        }
        if (ctx == null) {
            ctx = findNearestRecentPlayerContext(level, toPos, 12.0, 20L);
        }
        if (ctx == null) return;

        Optional<Action> parentOpt = findActionById(ctx.playerUuid(), ctx.actionId());
        if (parentOpt.isEmpty()) return;

        Action parentAction = parentOpt.get();
        FLUID_ORIGIN_ATTRIBUTION.put(toPos.immutable(), ctx);

        BlockDelta delta = new BlockDelta(toPos.immutable(), beforeState, afterState, beforeBeNbt, null);
        CascadeEntry entry = new CascadeEntry(
                delta,
                CascadeCause.FLUID_FLOW,
                parentAction.id(),
                ctx.nextSequence()
        );
        parentAction.appendCascade(entry);
    }

    public static void attachScheduledTickToActiveAction(ServerLevel level, ScheduledTickRecord tickRecord) {
        AttributionContext ctx = findNearestRecentPlayerContext(level, tickRecord.pos(), 16.0, 10L);
        if (ctx == null) return;
        findActionById(ctx.playerUuid(), ctx.actionId()).ifPresent(action -> action.addScheduledTick(tickRecord));
    }

    @Nullable
    private static AttributionContext resolveExplosionAttribution(ServerLevel level, Explosion explosion) {
        Entity directSource = explosion.getDirectSourceEntity();
        Entity indirectSource = explosion.getIndirectSourceEntity();

        if (indirectSource instanceof ServerPlayer player) {
            AttributionContext active = ACTIVE_PLAYER_CONTEXT.get(player.getUUID());
            if (active != null) return active;
        }
        if (directSource instanceof PrimedTnt tnt) {
            if (tnt.getOwner() instanceof ServerPlayer owner) {
                AttributionContext active = ACTIVE_PLAYER_CONTEXT.get(owner.getUUID());
                if (active != null) return active;
            }
            BlockPos tntPos = tnt.blockPosition();
            AttributionContext byPos = TNT_BLOCK_ATTRIBUTION.remove(tntPos);
            if (byPos != null) return byPos;
        }
        BlockPos center = BlockPos.containing(explosion.center());
        AttributionContext nearTnt = findNearestRecentPlayerContext(level, center, 24.0, 160L);
        if (nearTnt != null && (directSource instanceof PrimedTnt || CtrlZConfig.SERVER.recordUnattributed.get())) {
            return nearTnt;
        }
        return null;
    }

    @Nullable
    private static AttributionContext findNearestRecentPlayerContext(
            ServerLevel level, BlockPos pos, double maxRadius, long maxAgeTicks
    ) {
        long now = level.getGameTime();
        double maxDistSqr = maxRadius * maxRadius;
        AttributionContext best = null;
        double bestDist = Double.MAX_VALUE;

        for (AttributionContext ctx : ACTIVE_PLAYER_CONTEXT.values()) {
            if (now - ctx.createdGameTime() > maxAgeTicks) continue;
            double dist = ctx.originPos().distSqr(pos);
            if (dist <= maxDistSqr && dist < bestDist) {
                bestDist = dist;
                best = ctx;
            }
        }
        return best;
    }

    @Nullable
    private static AttributionContext findNearestFluidContext(
            ServerLevel level, BlockPos pos, double maxRadius, long maxAgeTicks
    ) {
        long now = level.getGameTime();
        double maxDistSqr = maxRadius * maxRadius;
        for (Map.Entry<BlockPos, AttributionContext> e : FLUID_ORIGIN_ATTRIBUTION.entrySet()) {
            AttributionContext ctx = e.getValue();
            if (now - ctx.createdGameTime() > maxAgeTicks) continue;
            if (e.getKey().distSqr(pos) <= maxDistSqr) {
                return ctx;
            }
        }
        return null;
    }

    private static Optional<Action> findActionById(UUID playerUuid, UUID actionId) {
        return HistoryManager.getOrCreateStack(playerUuid)
                .getUndoSnapshot()
                .stream()
                .filter(a -> a.id().equals(actionId))
                .findFirst();
    }

    public static void clearPlayerContexts(UUID playerUuid) {
        ACTIVE_PLAYER_CONTEXT.remove(playerUuid);
        FLUID_ORIGIN_ATTRIBUTION.entrySet().removeIf(e -> e.getValue().playerUuid().equals(playerUuid));
        TNT_BLOCK_ATTRIBUTION.entrySet().removeIf(e -> e.getValue().playerUuid().equals(playerUuid));
    }
}
