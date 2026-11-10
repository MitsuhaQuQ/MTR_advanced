package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ImportedImages;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.image.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.Painting;
import java.util.*;

/** Brush configuration for a vanilla painting; offsets and display size are in blocks. */
public final class ImportedPaintingScreen extends ScreenBase {
	private final Painting painting;
	private final List<ImageDatabase.Entry> images;
	private final WidgetBetterTextField[] fields = new WidgetBetterTextField[5];
	private final String[] labels = {"image_width", "image_height", "image_offset_x", "image_offset_y", "image_offset_z"};
	private int selection;
	private String error = "";
	public ImportedPaintingScreen(Painting painting) {
		super(Text.translatable("gui.mtr.image_settings")); this.painting = painting; ImportedImages.ensureConnection(); images = new ArrayList<>(ImportedImages.ENTRIES);
		PaintingSettings settings;
		try { settings = PaintingSettings.parse(((PaintingImage) painting).mtr$getImageSettings()); } catch (RuntimeException e) { settings = new PaintingSettings(); }
		if (settings.image.isEmpty()) { settings.width = painting.getVariant().value().getWidth() / 16.0; settings.height = painting.getVariant().value().getHeight() / 16.0; }
		for (int i = 0; i < images.size(); i++) if (images.get(i).name.equals(settings.image)) selection = i + 1;
		final double[] values = {settings.width, settings.height, settings.x, settings.y, settings.z};
		for (int i = 0; i < 5; i++) { fields[i] = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.SIGNED_DECIMAL, "", 16); fields[i].setValue(Double.toString(values[i])); }
	}
	@Override protected void init() {
		final int x = Math.max(10, width / 2 - 230), top = Math.max(32, height / 2 - 125);
		final Button choose = UtilitiesClient.newButton(Text.literal(selectionName()), button -> { selection = (selection + 1) % (images.size() + 1); button.setMessage(Text.literal(selectionName())); });
		mtr$button(choose, x, top, 270);
		mtr$button(UtilitiesClient.newButton(Text.literal("<"), button -> { selection = (selection + images.size()) % (images.size() + 1); choose.setMessage(Text.literal(selectionName())); }), x + 274, top, 24);
		for (int i = 0; i < fields.length; i++) { fields[i].setMaxLength(16); mtr$position(fields[i], x + 154, top + 32 + i * 25, 110); addDrawableChild(fields[i]); }
		mtr$button(UtilitiesClient.newButton(Text.translatable("gui.mtr.image_apply"), button -> {
			try { final PaintingSettings settings = new PaintingSettings(); settings.image = selection == 0 ? "" : images.get(selection - 1).name; settings.width = Double.parseDouble(fields[0].getValue()); settings.height = Double.parseDouble(fields[1].getValue()); settings.x = Double.parseDouble(fields[2].getValue()); settings.y = Double.parseDouble(fields[3].getValue()); settings.z = Double.parseDouble(fields[4].getValue()); settings.json(); ImportedImages.apply(painting.getId(), settings); onClose(); }
			catch (RuntimeException e) { error = "gui.mtr.image_invalid_settings"; }
		}), x, top + 170, 128);
		mtr$button(UtilitiesClient.newButton(Text.translatable("gui.mtr.image_cancel"), button -> onClose()), x + 136, top + 170, 128);
	}
	private String selectionName() { return selection == 0 ? Text.translatable("gui.mtr.image_vanilla").getString() : images.get(selection - 1).name; }
	private void mtr$button(Button button, int x, int y, int width) { mtr$position(button, x,y,width); addDrawableChild(button); }
	private void mtr$position(net.minecraft.client.gui.components.AbstractWidget widget, int x, int y, int width) { UtilitiesClient.setWidgetX(widget, x); UtilitiesClient.setWidgetY(widget, y); widget.setWidth(width); }
	@Override public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices); final int x = Math.max(10, width / 2 - 230), top = Math.max(32, height / 2 - 125);
		GuiCompat.draw(matrices, font, title, x, top - 24, 0xFFFFFF);
		for (int i = 0; i < labels.length; i++) GuiCompat.draw(matrices, font, Text.translatable("gui.mtr." + labels[i]), x, top + 37 + i * 25, 0xFFFFFF);
		GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.image_units"), x, top + 200, 0xAAAAAA);
		if (!error.isEmpty()) GuiCompat.draw(matrices, font, Text.translatable(error), x, top + 216, 0xFF5555);
		if (selection > 0 && width >= 620) {
			final ImageDatabase.Entry image = images.get(selection - 1); final ResourceLocation texture = ImportedImages.texture(image.hash);
			if (texture != null) { final double scale = Math.min(150.0 / image.width, 150.0 / image.height); final int w = (int) (image.width * scale), h = (int) (image.height * scale); GuiCompat.graphics(matrices).blit(texture, x + 310, top + 35, w, h, 0, 0, image.width, image.height, image.width, image.height); }
		}
		super.render(matrices, mouseX, mouseY, delta);
	}
	@Override public boolean isPauseScreen() { return false; }
}
