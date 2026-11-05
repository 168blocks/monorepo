package dev.vxcc.b8.mod.example.mixin;

import dev.vxcc.b8.mod.example.ExampleMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Example mixin (mixins stay in Java): counts server ticks for {@link ExampleMod}. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void blocks168$onTick(BooleanSupplier haveTime, CallbackInfo ci) {
		ExampleMod.INSTANCE.onServerTick((MinecraftServer) (Object) this);
	}
}
