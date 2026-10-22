package dev.aeronautics.ctrlz.mixin;

import dev.aeronautics.ctrlz.engine.UndoEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Предотвращает паразитные срабатывания поршней в момент пакетной записи блоков движком UndoEngine.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void aeronauticsCtrlZ$suppressPistonMoveDuringUndo(
            Level level, BlockPos pos, Direction facing, boolean extending, CallbackInfoReturnable<Boolean> cir
    ) {
        if (UndoEngine.isPerformingUndoRedo()) {
            cir.setReturnValue(false);
        }
    }
}
