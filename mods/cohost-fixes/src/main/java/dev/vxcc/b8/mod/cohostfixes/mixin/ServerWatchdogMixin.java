package dev.vxcc.b8.mod.cohostfixes.mixin;

import dev.vxcc.b8.mod.cohostfixes.CoHost;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.ServerWatchdog;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The watchdog exits the JVM (System.exit, then Runtime.halt) when its server's tick hangs. With other
 * servers in the JVM it stops only the hung one; the crash report is already written by then.
 */
@Mixin(ServerWatchdog.class)
abstract class ServerWatchdogMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private DedicatedServer server;

    @Inject(method = "exit", at = @At("HEAD"), cancellable = true)
    private void cohost$stopOnlyThisServer(CallbackInfo ci) {
        if (!CoHost.othersRunning(server)) return;
        LOGGER.error("Other servers run in this JVM: stopping only the hung server instead of exiting");
        server.halt(false);
        ci.cancel();
    }
}
