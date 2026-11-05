package dev.vxcc.b8.mod.cohostfixes.mixin;

import dev.vxcc.b8.mod.cohostfixes.CoHost;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks which servers run in this JVM ({@link CoHost}). */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void cohost$started(CallbackInfo ci) {
        CoHost.started((MinecraftServer) (Object) this);
    }

    // runServer calls onServerExit on every way out (normal stop, crash)
    @Inject(method = "onServerExit", at = @At("HEAD"))
    private void cohost$exited(CallbackInfo ci) {
        CoHost.exited((MinecraftServer) (Object) this);
    }
}
