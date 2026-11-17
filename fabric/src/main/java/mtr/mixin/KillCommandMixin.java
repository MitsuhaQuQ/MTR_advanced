package mtr.mixin;

import mtr.KillProtection;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Collection;

/**
 * Leaves what this mod keeps from /kill out of what it kills: paintings showing an imported picture (0.78.2), and MTR's
 * own entities when other entities are taken too (0.97.0); see {@link KillProtection}.
 */
@Mixin(KillCommand.class)
public abstract class KillCommandMixin {
	@ModifyVariable(method = "kill", at = @At("HEAD"), argsOnly = true)
	private static Collection<? extends Entity> mtr$keepPictures(Collection<? extends Entity> targets) {
		return KillProtection.withoutKept(targets);
	}
}
