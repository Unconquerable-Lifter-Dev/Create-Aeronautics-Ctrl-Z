package dev.aeronautics.ctrlz.mixin;

import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.recorder.ActionRecorder;
import dev.aeronautics.ctrlz.recorder.CascadeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехватывает растекание жидкостей (FlowingFluid#spreadTo) для записи каскадов смытых блоков
 * (факелы, редстоун, рельсы) и изменения уровней воды/лавы, вызванных игроком.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Inject(
            method = "spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD")
    )
    private void aeronauticsCtrlZ$onSpreadTo(
            LevelAccessor levelAccessor,
            BlockPos toPos,
            BlockState blockStateBefore,
            Direction direction,
            FluidState fluidStateAfter,
            CallbackInfo ci
    ) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (!(levelAccessor instanceof ServerLevel serverLevel)) return;

        BlockPos fromPos = toPos.relative(direction.getOpposite());
        CompoundTag beforeBe = ActionRecorder.captureBlockEntityNbt(serverLevel, serverLevel.getBlockEntity(toPos));
        BlockState afterBlockState = fluidStateAfter.createLegacyBlock();

        CascadeTracker.onFluidSpreadOrBlockChange(
                serverLevel,
                fromPos,
                toPos,
                blockStateBefore,
                afterBlockState,
                beforeBe
        );
    }
}
