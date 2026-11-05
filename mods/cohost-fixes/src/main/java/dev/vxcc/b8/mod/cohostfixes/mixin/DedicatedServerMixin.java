package dev.vxcc.b8.mod.cohostfixes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vxcc.b8.mod.cohostfixes.CoHost;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
    // DedicatedServer overrides onServerExit without calling super
    @Inject(method = "onServerExit", at = @At("HEAD"))
    private void cohost$exited(CallbackInfo ci) {
        CoHost.exited((MinecraftServer) (Object) this);
    }

    /**
     * stopServer shuts down Util's background, IO and download executors, which every server in the
     * JVM uses: only the last server to stop may do that.
     */
    @WrapOperation(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;shutdownExecutors()V"))
    private void cohost$keepSharedExecutors(Operation<Void> shutdown) {
        if (!CoHost.othersRunning((MinecraftServer) (Object) this)) shutdown.call();
    }
}
