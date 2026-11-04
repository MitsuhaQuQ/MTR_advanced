package mtr.mixin;

import mtr.client.SoundManagerAccess;
import mtr.client.UploadedSounds;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Lets the server's announcement audio into the game's sounds, again after each time the game loads its sounds
 * ({@link UploadedSounds}).
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin implements SoundManagerAccess {

	@Shadow
	@Final
	private Map<ResourceLocation, WeighedSoundEvents> registry;

	@Override
	public Map<ResourceLocation, WeighedSoundEvents> mtr$getSoundRegistry() {
		return registry;
	}

	@Inject(method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("TAIL"))
	private void afterApply(CallbackInfo ci) {
		UploadedSounds.refresh();
	}
}
