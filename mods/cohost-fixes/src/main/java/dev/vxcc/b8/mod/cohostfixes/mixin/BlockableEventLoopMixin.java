package dev.vxcc.b8.mod.cohostfixes.mixin;

import dev.vxcc.b8.mod.cohostfixes.CoHost;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

/**
 * {@code delayedCrash} is static: a crash delayed on one server's event loop is rethrown by every event
 * loop that propagates crashes, so one server's crash took all servers in the JVM down. It becomes a
 * field of each loop.
 *
 * <p>{@code relayDelayCrash} (static) comes from shared worker threads (chunk generation, Util's
 * executors), which do not know their server. With several servers running, the crash report is logged
 * instead of crashing every server; the failed task's future still fails for the server that waits on it.
 */
@Mixin(BlockableEventLoop.class)
abstract class BlockableEventLoopMixin {
    @Unique private static final Logger COHOST$LOGGER = com.mojang.logging.LogUtils.getLogger();
    @Unique private volatile Supplier<CrashReport> cohost$delayedCrash;

    @Redirect(method = "delayCrash", at = @At(value = "FIELD", target = "Lnet/minecraft/util/thread/BlockableEventLoop;delayedCrash:Ljava/util/function/Supplier;", opcode = Opcodes.PUTSTATIC))
    private void cohost$delayOnThisLoop(Supplier<CrashReport> crash) {
        cohost$delayedCrash = crash;
    }

    @Redirect(method = "throwDelayedException", at = @At(value = "FIELD", target = "Lnet/minecraft/util/thread/BlockableEventLoop;delayedCrash:Ljava/util/function/Supplier;", opcode = Opcodes.GETSTATIC))
    private Supplier<CrashReport> cohost$thisLoopsCrash() {
        Supplier<CrashReport> own = cohost$delayedCrash;
        return own != null ? own : cohost$relayed();
    }

    /** The static field, which only relayDelayCrash writes now (with a single server). */
    @Unique
    private static Supplier<CrashReport> cohost$relayed() {
        return BlockableEventLoopAccessor.cohost$getDelayedCrash();
    }

    @Inject(method = "relayDelayCrash", at = @At("HEAD"), cancellable = true)
    private static void cohost$noRelayWhenShared(CrashReport report, CallbackInfo ci) {
        if (!CoHost.shared()) return;
        COHOST$LOGGER.error("Crash on a shared worker thread, not relayed to the {} servers of this JVM:\n{}",
            CoHost.servers().size(), report.getFriendlyReport(net.minecraft.ReportType.CRASH));
        ci.cancel();
    }
}
