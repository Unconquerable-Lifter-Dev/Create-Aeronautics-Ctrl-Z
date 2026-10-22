package dev.aeronautics.ctrlz.mixin;

import dev.aeronautics.ctrlz.engine.UndoEngine;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Защищает мир от побочных эффектов взрыва во время активного выполнения атомарного Undo/Redo.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Inject(method = "finalizeExplosion", at = @At("HEAD"), cancellable = true)
    private void aeronauticsCtrlZ$preventExplosionDuringUndo(boolean spawnParticles, CallbackInfo ci) {
        if (UndoEngine.isPerformingUndoRedo()) {
            ci.cancel();
        }
    }
}
