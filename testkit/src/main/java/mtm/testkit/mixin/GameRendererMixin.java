package mtm.testkit.mixin;

import mtm.testkit.client.FrameStats;
import mtm.testkit.client.ScreenText;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks where a frame starts, so ScreenText keeps the text of the last whole frame and FrameStats can time frames.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

	@Inject(method = "render(FJZ)V", at = @At("HEAD"))
	private void mtmTestKit$nextFrame(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo callback) {
		ScreenText.nextFrame();
		FrameStats.nextFrame();
	}
}
