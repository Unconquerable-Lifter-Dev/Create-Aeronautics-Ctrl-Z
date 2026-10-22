package dev.aeronautics.ctrlz.mixin;

import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.model.ScheduledTickRecord;
import dev.aeronautics.ctrlz.recorder.ActionRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Перехватывает планирование отложенных тиков блоков и жидкостей (scheduled ticks) на ServerLevel,
 * чтобы при откате можно было детерминированно восстановить запланированные тики.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements WorldGenLevel {

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
        if (!UndoEngine.isPerformingUndoRedo()) {
            ServerLevel self = (ServerLevel) (Object) this;
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (blockId != null) {
                ActionRecorder.recordScheduledTickIfActive(
                        self,
                        new ScheduledTickRecord(pos.immutable(), blockId, false, delay, priority)
                );
            }
        }
        WorldGenLevel.super.scheduleTick(pos, block, delay, priority);
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay) {
        if (!UndoEngine.isPerformingUndoRedo()) {
            ServerLevel self = (ServerLevel) (Object) this;
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (blockId != null) {
                ActionRecorder.recordScheduledTickIfActive(
                        self,
                        new ScheduledTickRecord(pos.immutable(), blockId, false, delay, TickPriority.NORMAL)
                );
            }
        }
        WorldGenLevel.super.scheduleTick(pos, block, delay);
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        if (!UndoEngine.isPerformingUndoRedo()) {
            ServerLevel self = (ServerLevel) (Object) this;
            ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
            if (fluidId != null) {
                ActionRecorder.recordScheduledTickIfActive(
                        self,
                        new ScheduledTickRecord(pos.immutable(), fluidId, true, delay, priority)
                );
            }
        }
        WorldGenLevel.super.scheduleTick(pos, fluid, delay, priority);
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
        if (!UndoEngine.isPerformingUndoRedo()) {
            ServerLevel self = (ServerLevel) (Object) this;
            ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
            if (fluidId != null) {
                ActionRecorder.recordScheduledTickIfActive(
                        self,
                        new ScheduledTickRecord(pos.immutable(), fluidId, true, delay, TickPriority.NORMAL)
                );
            }
        }
        WorldGenLevel.super.scheduleTick(pos, fluid, delay);
    }
}
