package dev.aeronautics.ctrlz.mixin;

import dev.aeronautics.ctrlz.contraption.ContraptionStateHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Обеспечивает остановку (заморозку) тика физической сущности контрапшена
 * на время выполнения транзакции Undo/Redo для строгого детерминизма физики.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow public abstract UUID getUUID();

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void aeronauticsCtrlZ$freezeContraptionTickDuringUndo(CallbackInfo ci) {
        if (ContraptionStateHandler.isContraptionFrozen(this.getUUID())) {
            ci.cancel();
        }
    }
}
