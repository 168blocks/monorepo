package dev.vxcc.b8.mod.cohostfixes.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import dev.vxcc.b8.mod.cohostfixes.CoHost;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * C2ME (module {@code fixes.disableLoggingShutdownHook}) shuts log4j down and calls {@code System.exit}
 * when a DedicatedServer exits. With other servers in the JVM that would stop logging and end them all,
 * so the handler only runs for the last server. Applied only with C2ME ({@link CohostMixinPlugin}).
 */
@Mixin(value = DedicatedServer.class, priority = 1500)
abstract class C2meShutdownExitMixin {
    @TargetHandler(mixin = "com.ishland.c2me.base.mixin.util.log4j2shutdownhookisnomore.MixinMinecraftDedicatedServer", name = "onPostShutdown")
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true)
    private void cohost$exitOnlyWithTheLastServer(CallbackInfo original, CallbackInfo ci) {
        if (CoHost.othersRunning((MinecraftServer) (Object) this)) ci.cancel();
    }
}
