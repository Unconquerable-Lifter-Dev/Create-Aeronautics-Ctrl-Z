package dev.aeronautics.ctrlz.recorder;

import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.contraption.ContraptionStateHandler;
import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.history.HistoryManager;
import dev.aeronautics.ctrlz.model.Action;
import dev.aeronautics.ctrlz.model.ActionType;
import dev.aeronautics.ctrlz.model.BlockDelta;
import dev.aeronautics.ctrlz.model.ContraptionSnapshot;
import dev.aeronautics.ctrlz.model.ScheduledTickRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Серверный Recorder действий игрока:
 * - ломание и установка одиночных и мульти-блоков
 * - изменение параметров кинетических блоков (Speed, Direction, ScrollValue/ValueSettings, Motor, Bearing) через NBT
 * - взаимодействие гаечным ключом (wrench) и творческим посохом (wand of symmetry / schematicannon / worldshaper)
 * - автоматический захват состояния контрапшена до разрушения или модификации опорного блока
 */
public final class ActionRecorder {
    private static final Set<String> KINETIC_NBT_KEYS = Set.of(
            "Speed", "Value", "ScrollValue", "GeneratedSpeed", "MotorSpeed",
            "Direction", "RotationMode", "SequencedGearshift", "Running", "Assembled"
    );

    private ActionRecorder() {}

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onBlockBreak(final BlockEvent.BreakEvent event) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos().immutable();
        BlockState beforeState = event.getState();
        BlockEntity be = level.getBlockEntity(pos);
        if (CtrlZConfig.isBlockEntityBlacklisted(be)) {
            return;
        }

        CompoundTag beforeBeNbt = captureBlockEntityNbt(level, be);

        // Проверяем, не связан ли ломаемый блок с активным контрапшеном Create / Create Aeronautics
        ContraptionSnapshot contraptionSnapshot = null;
        if (CtrlZConfig.SERVER.enabled.get()) {
            contraptionSnapshot = ContraptionStateHandler.captureBeforeBlockBreak(level, pos, beforeState, beforeBeNbt);
        }

        BlockDelta delta = new BlockDelta(
                pos,
                beforeState,
                Blocks.AIR.defaultBlockState(),
                beforeBeNbt,
                null
        );

        ActionType actionType = contraptionSnapshot != null
                ? ActionType.CONTRAPTION_DISASSEMBLE
                : ActionType.BLOCK_BREAK;

        Action action = new Action(
                UUID.randomUUID(),
                player.getUUID(),
                player.getGameProfile().getName(),
                actionType,
                level.dimension(),
                List.of(delta),
                new ArrayList<>(),
                contraptionSnapshot,
                new ArrayList<>(),
                System.currentTimeMillis()
        );

        if (HistoryManager.recordAction(player, action)) {
            CascadeTracker.openPlayerActionContext(level, player.getUUID(), pos, action.id());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onMultiBlockPlace(final BlockEvent.EntityMultiPlaceEvent event) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        List<BlockDelta> deltas = new ArrayList<>();
        for (BlockSnapshot snap : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snap.getPos().immutable();
            BlockState beforeState = snap.getState();
            BlockState afterState = level.getBlockState(pos);
            BlockEntity afterBe = level.getBlockEntity(pos);
            if (CtrlZConfig.isBlockEntityBlacklisted(afterBe)) {
                return;
            }
            CompoundTag afterBeNbt = captureBlockEntityNbt(level, afterBe);
            deltas.add(new BlockDelta(pos, beforeState, afterState, null, afterBeNbt));
        }

        if (deltas.isEmpty()) return;

        ActionType type = isCreativeWandItem(player.getMainHandItem())
                ? ActionType.WRENCH_INTERACT
                : ActionType.BLOCK_PLACE;

        Action action = new Action(
                UUID.randomUUID(),
                player.getUUID(),
                player.getGameProfile().getName(),
                type,
                level.dimension(),
                deltas,
                new ArrayList<>(),
                null,
                new ArrayList<>(),
                System.currentTimeMillis()
        );

        if (HistoryManager.recordAction(player, action)) {
            CascadeTracker.openPlayerActionContext(level, player.getUUID(), event.getPos(), action.id());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onSingleBlockPlace(final BlockEvent.EntityPlaceEvent event) {
        if (event instanceof BlockEvent.EntityMultiPlaceEvent) {
            return; // Уже обработано в onMultiBlockPlace
        }
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos().immutable();
        BlockState beforeState = event.getBlockSnapshot().getState();
        BlockState afterState = event.getPlacedBlock();
        BlockEntity afterBe = level.getBlockEntity(pos);
        if (CtrlZConfig.isBlockEntityBlacklisted(afterBe)) {
            return;
        }

        CompoundTag afterBeNbt = captureBlockEntityNbt(level, afterBe);
        BlockDelta delta = new BlockDelta(pos, beforeState, afterState, null, afterBeNbt);

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
            CascadeTracker.openPlayerActionContext(level, player.getUUID(), pos, action.id());
            if (afterState.is(Blocks.TNT)) {
                CascadeTracker.attributeTntPlacement(level, pos, player.getUUID(), action.id());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = false)
    public static void onRightClickBlockPre(final PlayerInteractEvent.RightClickBlock event) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos().immutable();
        BlockState beforeState = level.getBlockState(pos);
        BlockEntity beforeBe = level.getBlockEntity(pos);
        CompoundTag beforeNbt = captureBlockEntityNbt(level, beforeBe);

        ItemStack held = event.getItemStack();
        if (held.getItem() instanceof BucketItem) {
            CascadeTracker.recordPreFluidBucketUse(level, player, pos.relative(event.getFace() != null ? event.getFace() : net.minecraft.core.Direction.UP));
        } else if (held.getItem() instanceof FlintAndSteelItem && beforeState.is(Blocks.TNT)) {
            CascadeTracker.recordPreTntIgnition(level, player, pos, beforeState);
        }

        // Сохраняем предоперационный снимок блока/BE для отслеживания изменений параметров Create, гаечного ключа или сборки подшипника
        InteractionSnapshotTracker.pushPreInteraction(level, player, pos, beforeState, beforeNbt, held);
    }

    @Nullable
    public static CompoundTag captureBlockEntityNbt(ServerLevel level, @Nullable BlockEntity be) {
        if (be == null) return null;
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        if (!CtrlZConfig.SERVER.restoreInventories.get()) {
            stripInventoryTagsIfConfigured(tag);
        }
        return tag;
    }

    private static void stripInventoryTagsIfConfigured(CompoundTag tag) {
        tag.remove("Items");
        tag.remove("Inventory");
        tag.remove("InputInventory");
        tag.remove("OutputInventory");
    }

    public static boolean isKineticOrContraptionParameterNbt(CompoundTag nbt) {
        if (nbt == null) return false;
        for (String key : KINETIC_NBT_KEYS) {
            if (nbt.contains(key)) {
                return true;
            }
        }
        String id = nbt.getString("id");
        return id.startsWith("create:") || id.startsWith("create_aeronautics:");
    }

    public static boolean isWrenchOrCreativeWand(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) return false;
        String path = itemId.getPath();
        return path.contains("wrench") || path.contains("wand") || path.contains("handheld_worldshaper");
    }

    public static boolean isCreativeWandItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return itemId != null && (itemId.getPath().contains("wand") || itemId.getPath().contains("worldshaper"));
    }

    /**
     * Вызывается из BlockEntityMixin при setChanged() или из тик-обработчика после клика игрока.
     */
    public static void onBlockEntityModified(ServerLevel level, BlockPos pos, BlockState currentState, BlockEntity be) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (CtrlZConfig.isBlockEntityBlacklisted(be)) return;

        InteractionSnapshotTracker.PendingInteraction pending = InteractionSnapshotTracker.pollMatching(level, pos);
        if (pending == null) return;

        CompoundTag afterNbt = captureBlockEntityNbt(level, be);
        if (pending.beforeNbt() != null && pending.beforeNbt().equals(afterNbt) && pending.beforeState().equals(currentState)) {
            return;
        }

        ActionType type = ActionType.KINETIC_PARAM_CHANGE;
        if (isWrenchOrCreativeWand(pending.heldStack())) {
            type = ActionType.WRENCH_INTERACT;
        }

        ContraptionSnapshot contraptionSnapshot = null;
        if (CtrlZConfig.SERVER.enabled.get() && ContraptionStateHandler.isBearingOrContraptionController(currentState, afterNbt)) {
            contraptionSnapshot = ContraptionStateHandler.captureControllerStateChange(
                    level, pos, pending.beforeState(), currentState, pending.beforeNbt(), afterNbt
            );
            if (contraptionSnapshot != null) {
                type = contraptionSnapshot.assembledAfter()
                        ? ActionType.CONTRAPTION_ASSEMBLE
                        : ActionType.CONTRAPTION_DISASSEMBLE;
            }
        }

        BlockDelta delta = new BlockDelta(pos, pending.beforeState(), currentState, pending.beforeNbt(), afterNbt);
        Action action = new Action(
                UUID.randomUUID(),
                pending.player().getUUID(),
                pending.player().getGameProfile().getName(),
                type,
                level.dimension(),
                List.of(delta),
                new ArrayList<>(),
                contraptionSnapshot,
                new ArrayList<>(),
                System.currentTimeMillis()
        );
        HistoryManager.recordAction(pending.player(), action);
    }

    public static void recordScheduledTickIfActive(ServerLevel level, ScheduledTickRecord tickRecord) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!CtrlZConfig.SERVER.restoreScheduledTicks.get()) return;
        CascadeTracker.attachScheduledTickToActiveAction(level, tickRecord);
    }
}
