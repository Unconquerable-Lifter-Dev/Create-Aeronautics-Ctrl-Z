package dev.aeronautics.ctrlz.gametest;

import dev.aeronautics.ctrlz.AeronauticsCtrlZMod;
import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.engine.UndoResult;
import dev.aeronautics.ctrlz.history.HistoryManager;
import dev.aeronautics.ctrlz.model.Action;
import dev.aeronautics.ctrlz.model.ActionType;
import dev.aeronautics.ctrlz.model.BlockDelta;
import dev.aeronautics.ctrlz.model.CascadeCause;
import dev.aeronautics.ctrlz.model.CascadeEntry;
import dev.aeronautics.ctrlz.model.ContraptionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Автоматические серверные GameTest-сценарии P0:
 * 1. Сломал блок -> контрапшен распался -> Undo -> собрался обратно.
 * 2. Взрыв TNT -> каскадные разрушения -> атомарный Undo.
 * 3. Поршень -> сдвиг конструкции -> атомарный Undo.
 * 4. Жидкость (лава/вода) -> смытые блоки -> атомарный Undo.
 */
@GameTestHolder(AeronauticsCtrlZMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CtrlZGameTests {

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void testContraptionBreakAndUndoReassembles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mockPlayer = helper.makeMockServerPlayerInLevel(GameType.SURVIVAL);
        HistoryManager.getOrCreateStack(mockPlayer.getUUID()).clear();

        BlockPos anchorWorld = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wingWorld = helper.absolutePos(new BlockPos(3, 2, 2));

        // Исходная собранная конструкция: опорный блок + крыло контрапшена
        level.setBlockAndUpdate(anchorWorld, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(wingWorld, Blocks.OAK_PLANKS.defaultBlockState());

        CompoundTag controllerBefore = new CompoundTag();
        controllerBefore.putBoolean("Running", true);
        controllerBefore.putBoolean("Assembled", true);

        List<BlockDelta> contraptionBlocks = List.of(
                new BlockDelta(anchorWorld, Blocks.IRON_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState(), controllerBefore, null),
                new BlockDelta(wingWorld, Blocks.OAK_PLANKS.defaultBlockState(), Blocks.AIR.defaultBlockState(), null, null)
        );

        ContraptionSnapshot snapshot = new ContraptionSnapshot(
                UUID.randomUUID(),
                anchorWorld,
                true,
                false,
                true,
                false,
                Vec3.atCenterOf(anchorWorld),
                Vec3.ZERO,
                Vec3.ZERO,
                new CompoundTag(),
                controllerBefore,
                new CompoundTag(),
                contraptionBlocks,
                List.of()
        );

        // Игрок ломает опорный блок, контрапшен распадается
        level.setBlockAndUpdate(anchorWorld, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(wingWorld, Blocks.AIR.defaultBlockState());

        Action breakAction = new Action(
                UUID.randomUUID(),
                mockPlayer.getUUID(),
                mockPlayer.getGameProfile().getName(),
                ActionType.CONTRAPTION_DISASSEMBLE,
                level.dimension(),
                List.of(contraptionBlocks.getFirst()),
                new ArrayList<>(),
                snapshot,
                new ArrayList<>(),
                System.currentTimeMillis()
        );
        HistoryManager.recordAction(mockPlayer, breakAction);

        // Выполняем Undo -> конструкция и блоки контрапшена полностью собираются обратно
        UndoResult undoRes = UndoEngine.executeUndo(mockPlayer, mockPlayer.getUUID(), false);
        helper.assertTrue(undoRes.success(), "Contraption undo must succeed: " + undoRes.detail());
        helper.assertBlockPresent(Blocks.IRON_BLOCK, new BlockPos(2, 2, 2));
        helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(3, 2, 2));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void testTntExplosionCascadeAtomicUndo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mockPlayer = helper.makeMockServerPlayerInLevel(GameType.SURVIVAL);
        HistoryManager.getOrCreateStack(mockPlayer.getUUID()).clear();

        BlockPos tntPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos stone1 = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos stone2 = helper.absolutePos(new BlockPos(2, 2, 3));

        level.setBlockAndUpdate(tntPos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(stone1, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(stone2, Blocks.AIR.defaultBlockState());

        UUID actionId = UUID.randomUUID();
        BlockDelta igniteDelta = new BlockDelta(tntPos, Blocks.TNT.defaultBlockState(), Blocks.AIR.defaultBlockState(), null, null);
        List<CascadeEntry> cascades = List.of(
                new CascadeEntry(new BlockDelta(stone1, Blocks.STONE_BRICKS.defaultBlockState(), Blocks.AIR.defaultBlockState(), null, null), CascadeCause.TNT_EXPLOSION, actionId, 1),
                new CascadeEntry(new BlockDelta(stone2, Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState(), null, null), CascadeCause.TNT_EXPLOSION, actionId, 2)
        );

        Action explosionAction = new Action(
                actionId,
                mockPlayer.getUUID(),
                mockPlayer.getGameProfile().getName(),
                ActionType.CASCADE_COMPOSITE,
                level.dimension(),
                List.of(igniteDelta),
                cascades,
                null,
                List.of(),
                System.currentTimeMillis()
        );
        HistoryManager.recordAction(mockPlayer, explosionAction);

        UndoResult res = UndoEngine.executeUndo(mockPlayer, mockPlayer.getUUID(), false);
        helper.assertTrue(res.success(), "TNT cascade undo must succeed");
        helper.assertBlockPresent(Blocks.TNT, new BlockPos(2, 2, 2));
        helper.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(3, 2, 2));
        helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(2, 2, 3));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void testPistonPushCascadeUndo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mockPlayer = helper.makeMockServerPlayerInLevel(GameType.SURVIVAL);
        HistoryManager.getOrCreateStack(mockPlayer.getUUID()).clear();

        BlockPos originPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos pushedPos = helper.absolutePos(new BlockPos(3, 2, 2));

        // Состояние после сдвига поршнем: блок переместился из (2,2,2) в (3,2,2)
        level.setBlockAndUpdate(originPos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pushedPos, Blocks.COPPER_BLOCK.defaultBlockState());

        UUID actionId = UUID.randomUUID();
        List<CascadeEntry> pistonCascades = List.of(
                new CascadeEntry(new BlockDelta(originPos, Blocks.COPPER_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState(), null, null), CascadeCause.PISTON_PUSH_PULL, actionId, 1),
                new CascadeEntry(new BlockDelta(pushedPos, Blocks.AIR.defaultBlockState(), Blocks.COPPER_BLOCK.defaultBlockState(), null, null), CascadeCause.PISTON_PUSH_PULL, actionId, 2)
        );

        Action pistonAction = new Action(
                actionId,
                mockPlayer.getUUID(),
                mockPlayer.getGameProfile().getName(),
                ActionType.BLOCK_PLACE,
                level.dimension(),
                List.of(),
                pistonCascades,
                null,
                List.of(),
                System.currentTimeMillis()
        );
        HistoryManager.recordAction(mockPlayer, pistonAction);

        UndoResult res = UndoEngine.executeUndo(mockPlayer, mockPlayer.getUUID(), false);
        helper.assertTrue(res.success(), "Piston cascade undo must succeed");
        helper.assertBlockPresent(Blocks.COPPER_BLOCK, new BlockPos(2, 2, 2));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 2, 2));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void testFluidWashCascadeUndo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mockPlayer = helper.makeMockServerPlayerInLevel(GameType.SURVIVAL);
        HistoryManager.getOrCreateStack(mockPlayer.getUUID()).clear();

        BlockPos sourcePos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos torchPos = helper.absolutePos(new BlockPos(3, 2, 2));

        // Состояние после разлива: источник лавы/воды и смытый редстоун-факел
        level.setBlockAndUpdate(sourcePos, Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(torchPos, Blocks.WATER.defaultBlockState());

        UUID actionId = UUID.randomUUID();
        BlockDelta bucketPlace = new BlockDelta(sourcePos, Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(), null, null);
        CascadeEntry washedTorch = new CascadeEntry(
                new BlockDelta(torchPos, Blocks.REDSTONE_TORCH.defaultBlockState(), Blocks.WATER.defaultBlockState(), null, null),
                CascadeCause.FLUID_FLOW,
                actionId,
                1
        );

        Action fluidAction = new Action(
                actionId,
                mockPlayer.getUUID(),
                mockPlayer.getGameProfile().getName(),
                ActionType.BLOCK_PLACE,
                level.dimension(),
                List.of(bucketPlace),
                List.of(washedTorch),
                null,
                List.of(),
                System.currentTimeMillis()
        );
        HistoryManager.recordAction(mockPlayer, fluidAction);

        UndoResult res = UndoEngine.executeUndo(mockPlayer, mockPlayer.getUUID(), false);
        helper.assertTrue(res.success(), "Fluid cascade undo must succeed");
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 2, 2));
        helper.assertBlockPresent(Blocks.REDSTONE_TORCH, new BlockPos(3, 2, 2));
        helper.succeed();
    }
}
