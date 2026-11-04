package mtr.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import mtr.client.UploadedSounds;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The audio of the server's announcement sounds comes from the files this client has, not the resource packs
 * ({@link UploadedSounds}).
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {

	@Shadow
	@Final
	private Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache;

	@Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
	private void getCompleteBuffer(ResourceLocation location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
		if (UploadedSounds.isUploadedPath(location)) {
			cir.setReturnValue(UploadedSounds.getBuffer(cache, location));
		}
	}
}
