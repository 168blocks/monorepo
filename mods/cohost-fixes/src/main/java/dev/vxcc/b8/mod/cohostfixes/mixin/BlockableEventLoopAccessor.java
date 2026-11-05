package dev.vxcc.b8.mod.cohostfixes.mixin;

import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockableEventLoop.class)
interface BlockableEventLoopAccessor {
    @Accessor("delayedCrash")
    static Supplier<CrashReport> cohost$getDelayedCrash() {
        throw new AssertionError();
    }
}
