package dev.vxcc.b8.mod.creativehost.mixin;

import dev.vxcc.b8.mod.creativehost.SharedResources;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A server closes its datapack resources when it stops and when {@code /reload} replaces them. Hosted
 * servers share the host's resource manager ({@link SharedResources}), so only its last user closes it.
 */
@Mixin(targets = "net.minecraft.server.MinecraftServer$ReloadableResources")
public abstract class ReloadableResourcesMixin {
	@Shadow
	@Final
	private CloseableResourceManager resourceManager;

	@Inject(method = "close", at = @At("HEAD"), cancellable = true)
	private void blocks168$closeLastUserOnly(CallbackInfo ci) {
		if (!SharedResources.INSTANCE.release(resourceManager)) ci.cancel();
	}
}
