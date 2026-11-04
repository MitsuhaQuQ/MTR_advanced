package mtr.client;

import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * What a mixin of each loader gives the game's sound manager, so {@link UploadedSounds} can add the server's announcement
 * audio to the sounds the game knows.
 */
public interface SoundManagerAccess {

	Map<ResourceLocation, WeighedSoundEvents> mtr$getSoundRegistry();
}
