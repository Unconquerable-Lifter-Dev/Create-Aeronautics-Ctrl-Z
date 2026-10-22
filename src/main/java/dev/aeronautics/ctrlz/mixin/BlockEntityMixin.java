package dev.aeronautics.ctrlz.mixin;

import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.recorder.ActionRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Отслеживает вызовы BlockEntity#setChanged() сразу после взаимодействия игрока
 * (изменение скорости вращения, направления мотора, настроек подшипника Create и т.д.).
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Shadow @Nullable protected Level level;
    @Shadow @Final protected BlockPos worldPosition;
    @Shadow public abstract BlockState getBlockState();

    @Inject(method = "setChanged()V", at = @At("TAIL"))
    private void aeronauticsCtrlZ$onSetChanged(CallbackInfo ci) {
        if (UndoEngine.isPerformingUndoRedo()) return;
        if (this.level instanceof ServerLevel serverLevel) {
            BlockEntity self = (BlockEntity) (Object) this;
            ActionRecorder.onBlockEntityModified(serverLevel, this.worldPosition, this.getBlockState(), self);
        }
    }
}
