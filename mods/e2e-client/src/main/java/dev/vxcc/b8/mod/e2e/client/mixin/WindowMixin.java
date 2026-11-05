package dev.vxcc.b8.mod.e2e.client.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A window on a headless compositor never receives focus, but the game ignores held keys and mouse
 * look without it (and may pause). The test client always counts as focused.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
    private void blocks168e2e$alwaysFocused(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
