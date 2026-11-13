package mtr.mixin;

import mtr.image.PaintingProtection;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Collection;

/**
 * Leaves paintings showing an imported picture out of what /kill kills (0.78.2), so /kill @e doesn't clear them; see
 * {@link PaintingProtection}.
 */
@Mixin(KillCommand.class)
public abstract class KillCommandMixin {
	@ModifyVariable(method = "kill", at = @At("HEAD"), argsOnly = true)
	private static Collection<? extends Entity> mtr$keepPictures(Collection<? extends Entity> targets) {
		return PaintingProtection.withoutProtected(targets);
	}
}
