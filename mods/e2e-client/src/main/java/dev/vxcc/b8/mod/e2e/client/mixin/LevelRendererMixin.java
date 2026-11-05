package dev.vxcc.b8.mod.e2e.client.mixin;

import dev.vxcc.b8.mod.e2e.client.E2eClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts frames ({@code Minecraft.runTick} calls {@code LevelRenderer.endFrame} once per frame), so the
 * RPC can wait for a number of frames presented after the world was rendered, not for a time.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "endFrame", at = @At("TAIL"))
    private void blocks168e2e$countFrame(CallbackInfo ci) {
        E2eClient.INSTANCE.getFrames().incrementAndGet();
    }
}
