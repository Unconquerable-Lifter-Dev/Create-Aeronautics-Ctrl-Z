package dev.aeronautics.ctrlz.mixin;

import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.model.ScheduledTickRecord;
import dev.aeronautics.ctrlz.recorder.ActionRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехватывает планирование отложенных тиков блоков и жидкостей (scheduled ticks) на ServerLevel,
 * чтобы при откате можно было детерминированно восстановить запланированные тики.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(
            method = "scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;ILnet/minecraft/world/ticks/TickPriority;)V",
            at = @At("HEAD")
    )
    private void aeronauticsCtrlZ$onScheduleBlockTick(
            BlockPos pos, BlockState state, int delay, TickPriority priority, CallbackInfo ci
    ) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        ServerLevel self = (ServerLevel) (Object) this;
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockId != null) {
            ActionRecorder.recordScheduledTickIfActive(
                    self,
                    new ScheduledTickRecord(pos.immutable(), blockId, false, delay, priority)
            );
        }
    }

    @Inject(
            method = "scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;ILnet/minecraft/world/ticks/TickPriority;)V",
            at = @At("HEAD")
    )
    private void aeronauticsCtrlZ$onScheduleFluidTick(
            BlockPos pos, Fluid fluid, int delay, TickPriority priority, CallbackInfo ci
    ) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        ServerLevel self = (ServerLevel) (Object) this;
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId != null) {
            ActionRecorder.recordScheduledTickIfActive(
                    self,
                    new ScheduledTickRecord(pos.immutable(), fluidId, true, delay, priority)
            );
        }
    }
}
