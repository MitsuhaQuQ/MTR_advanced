package mtm.testkit.mixin;

import mtm.testkit.client.ScreenText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every text the font draws (ScreenText), at the two methods all of Font's drawing goes through.
 */
@Mixin(Font.class)
public class FontMixin {

	@Inject(method = "drawInternal(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I", at = @At("HEAD"))
	private void mtmTestKit$recordString(String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode displayMode, int backgroundColor, int packedLight, boolean bidirectional, CallbackInfoReturnable<Integer> callback) {
		ScreenText.record(text, x, y, color, matrix);
	}

	@Inject(method = "drawInternal(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I", at = @At("HEAD"))
	private void mtmTestKit$recordSequence(FormattedCharSequence text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode displayMode, int backgroundColor, int packedLight, CallbackInfoReturnable<Integer> callback) {
		final StringBuilder builder = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			builder.appendCodePoint(codePoint);
			return true;
		});
		ScreenText.record(builder.toString(), x, y, color, matrix);
	}
}
