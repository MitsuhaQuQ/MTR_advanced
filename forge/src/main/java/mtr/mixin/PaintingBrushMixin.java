package mtr.mixin;

import mtr.Items;
import mtr.screen.ImportedPaintingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class PaintingBrushMixin {
	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void mtr$brush(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (target instanceof Painting && player.getItemInHand(hand).is(Items.BRUSH.get())) {
			Minecraft.getInstance().setScreen(new ImportedPaintingScreen((Painting) target)); cir.setReturnValue(InteractionResult.SUCCESS);
		}
	}
}
