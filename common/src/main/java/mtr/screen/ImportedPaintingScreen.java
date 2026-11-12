package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ImportedImages;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.image.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.Painting;
import java.util.*;

/** Draft values preview locally; only Apply sends settings to the server. */
public final class ImportedPaintingScreen extends ScreenBase {
	private final Painting painting;
	private final List<ImageDatabase.Entry> images;
	private final WidgetBetterTextField[] fields = new WidgetBetterTextField[8];
	private final String[] labels = {"image_width", "image_height", "image_offset_x", "image_offset_y", "image_offset_z", "image_rotation_x", "image_rotation_y", "image_rotation_z"};
	private String selectedImage, error = "";
	private boolean emissive;
	private int left, top;
	private Button choose;
	private boolean dropdownOpen;
	private int dropdownScroll, highlighted;
	/**
	 * Typed while the list is open (0.65.28): the list shows the images whose names have every word of it, and the
	 * original painting only while it is blank. The rows shown, null for the original painting.
	 */
	private String search = "";
	private final List<ImageDatabase.Entry> shown = new ArrayList<>();
	private static final int SEARCH_HEIGHT = 14;
	private int previousMouseX = Integer.MIN_VALUE, previousMouseY = Integer.MIN_VALUE;
	public ImportedPaintingScreen(Painting painting) {
		super(Text.translatable("gui.mtr.image_settings"));
		this.painting = painting;
		ImportedImages.ensureConnection();
		images = new ArrayList<>(ImportedImages.ENTRIES);
		PaintingSettings settings;
		try { settings = PaintingSettings.parse(((PaintingImage) painting).mtr$getImageSettings()); }
		catch (RuntimeException e) { settings = new PaintingSettings(); }
		if (settings.image.isEmpty()) {
			settings.width = painting.getVariant().value().getWidth() / 16.0;
			settings.height = painting.getVariant().value().getHeight() / 16.0;
		}
		selectedImage = settings.image;
		emissive = settings.emissive;
		final double[] values = {settings.width, settings.height, settings.x, settings.y, settings.z, settings.rotationX, settings.rotationY, settings.rotationZ};
		for (int i = 0; i < fields.length; i++) {
			fields[i] = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.SIGNED_DECIMAL, "", 16);
			fields[i].setValue(Double.toString(values[i]));
		}
	}
	@Override protected void init() {
		left = Math.max(8, width / 2 - 150);
		top = Math.max(22, (height - 258) / 2);
		dropdownOpen = false;
		choose = UtilitiesClient.newButton(Text.literal(selectionName() + " ▾"), button -> {
			images.clear(); images.addAll(ImportedImages.ENTRIES);
			search = "";
			refilter();
			for (int i = 0; i < shown.size(); i++) if (shown.get(i) != null && shown.get(i).name.equals(selectedImage)) highlighted = i;
			dropdownScroll = Math.max(0, Math.min(highlighted, shown.size() - dropdownRows()));
			for (WidgetBetterTextField field : fields) field.setFocused(false);
			dropdownOpen = true;
		});
		place(choose, left, top, 298);
		for (int i = 0; i < fields.length; i++) {
			place(fields[i], left + 182, top + 26 + i * 21, 110);
			fields[i].moveCursorToStart();
		}
		place(UtilitiesClient.newButton(Text.translatable("gui.mtr.image_gizmo"), button -> {
			try { draft(); minecraft.setScreen(new PaintingRotationScreen(this)); }
			catch (RuntimeException e) { error = "gui.mtr.image_invalid_settings"; }
		}), left + 151, top + 198, 147);
		place(UtilitiesClient.newButton(emissiveLabel(), button -> { emissive = !emissive; button.setMessage(emissiveLabel()); }), left, top + 198, 147);
		place(UtilitiesClient.newButton(Text.translatable("gui.mtr.image_apply"), button -> {
			try { ImportedImages.apply(painting.getId(), draft()); onClose(); }
			catch (RuntimeException e) { error = "gui.mtr.image_invalid_settings"; }
		}), left, top + 222, 147);
		place(UtilitiesClient.newButton(Text.translatable("gui.mtr.image_cancel"), button -> onClose()), left + 151, top + 222, 147);
	}
	/** At least one row, for saying nothing matches */
	private int dropdownRows() { return Math.max(1, Math.min(shown.size(), Math.max(1, Math.min(6, (height - top - 32 - SEARCH_HEIGHT) / 32)))); }
	private int rowsTop() { return top + 22 + SEARCH_HEIGHT; }
	/** The rows matching the search, the first one highlighted */
	private void refilter() {
		shown.clear();
		if (search.trim().isEmpty() || ImageSearch.matches(Text.translatable("gui.mtr.image_vanilla").getString(), search)) shown.add(null);
		final List<String> names = new ArrayList<>();
		images.forEach(entry -> names.add(entry.name));
		ImageSearch.filter(names, search).forEach(index -> shown.add(images.get(index)));
		highlighted = 0;
		dropdownScroll = 0;
	}
	private void selectImage(int index) {
		if (index < 0 || index >= shown.size()) return;
		selectedImage = shown.get(index) == null ? "" : shown.get(index).name;
		choose.setMessage(Text.literal(selectionName() + " ▾")); dropdownOpen = false;
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (!dropdownOpen) return super.mouseClicked(x,y,button);
		final boolean inList = x >= left && x < left + 298;
		if (button == 0 && inList && y >= top + 22 && y < rowsTop()) return true;
		if (button == 0 && inList && y >= rowsTop() && y < rowsTop() + dropdownRows() * 32) selectImage(dropdownScroll + (int)(y - rowsTop()) / 32);
		else dropdownOpen = false;
		return true;
	}
	@Override public boolean mouseScrolled(double x, double y, double amount) {
		if (!dropdownOpen) return super.mouseScrolled(x,y,amount);
		dropdownScroll = Math.max(0, Math.min(shown.size() - dropdownRows(), dropdownScroll - (int)Math.signum(amount)));
		return true;
	}
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (!dropdownOpen) return super.keyPressed(key,scan,modifiers);
		// Escape clears the search first, then closes the list; typing searches, so space is a letter of the search
		if (key == 256) {
			if (search.isEmpty()) dropdownOpen = false;
			else { search = ""; refilter(); }
		} else if (key == 259) {
			if (!search.isEmpty()) { search = search.substring(0, search.offsetByCodePoints(search.length(), -1)); refilter(); }
		} else if (key == 257 || key == 335) {
			selectImage(highlighted);
		} else if (key == 264 || key == 265) {
			highlighted = Math.max(0, Math.min(shown.size() - 1, highlighted + (key == 264 ? 1 : -1)));
			dropdownScroll = Math.max(0, Math.min(highlighted, Math.max(dropdownScroll, highlighted - dropdownRows() + 1)));
		}
		return true;
	}
	@Override public boolean charTyped(char character, int modifiers) {
		if (!dropdownOpen) return super.charTyped(character,modifiers);
		if (!Character.isISOControl(character) && search.length() < 64) { search += character; refilter(); }
		return true;
	}
	private net.minecraft.network.chat.Component emissiveLabel() { return Text.translatable(emissive ? "gui.mtr.image_emissive_on" : "gui.mtr.image_emissive_off"); }
	private String selectionName() { return selectedImage.isEmpty() ? Text.translatable("gui.mtr.image_vanilla").getString() : selectedImage; }
	private void place(net.minecraft.client.gui.components.AbstractWidget widget, int x, int y, int width) {
		UtilitiesClient.setWidgetX(widget, x); UtilitiesClient.setWidgetY(widget, y); widget.setWidth(width); addDrawableChild(widget);
	}
	PaintingSettings draft() {
		final PaintingSettings s = new PaintingSettings();
		s.image = selectedImage;
		s.emissive = emissive;
		s.width = Double.parseDouble(fields[0].getValue()); s.height = Double.parseDouble(fields[1].getValue());
		s.x = Double.parseDouble(fields[2].getValue()); s.y = Double.parseDouble(fields[3].getValue()); s.z = Double.parseDouble(fields[4].getValue());
		s.rotationX = Double.parseDouble(fields[5].getValue()); s.rotationY = Double.parseDouble(fields[6].getValue()); s.rotationZ = Double.parseDouble(fields[7].getValue());
		s.json();
		return s;
	}
	void setRotation(int axis, double angle) { fields[5 + axis].setValue(String.format(Locale.ROOT, "%.1f", PaintingTransform.wrap(angle))); }
	public static PaintingSettings previewFor(Painting painting) {
		final net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
		final ImportedPaintingScreen editor = screen instanceof ImportedPaintingScreen ? (ImportedPaintingScreen) screen :
				screen instanceof PaintingRotationScreen ? ((PaintingRotationScreen) screen).editor : null;
		if (editor == null || editor.painting != painting) return null;
		try { return editor.draft(); } catch (RuntimeException e) { return null; }
	}
	@Override public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices);
		GuiCompat.draw(matrices, font, title, left, top - 16, 0xFFFFFF);
		for (int i = 0; i < labels.length; i++) GuiCompat.draw(matrices, font, Text.translatable("gui.mtr." + labels[i]), left, top + 32 + i * 21, 0xFFFFFF);
		if (!error.isEmpty()) GuiCompat.draw(matrices, font, Text.translatable(error), left, top + 247, 0xFF5555);
		super.render(matrices, mouseX, mouseY, delta);
		if (dropdownOpen) renderDropdown(matrices, mouseX, mouseY);
	}
	private void renderDropdown(PoseStack matrices, int mouseX, int mouseY) {
		matrices.pushPose(); matrices.translate(0,0,400);
		GuiGraphics graphics = GuiCompat.graphics(matrices);
		int rows = dropdownRows(), searchY = top + 22, y = rowsTop();
		graphics.fill(left-1,searchY-1,left+299,y+rows*32+1,0xFFAAAAAA);
		graphics.fill(left,searchY,left+298,y+rows*32,0xFF202020);
		// The search line: what was typed with a cursor, or how to search
		graphics.fill(left,searchY,left+298,y-1,0xFF303030);
		final String searchText = search.isEmpty() ? Text.translatable("gui.mtr.image_search_hint").getString() : search + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
		graphics.drawString(font,font.plainSubstrByWidth(searchText,288),left+4,searchY+3,search.isEmpty() ? 0xFF888888 : 0xFFFFFFFF,false);
		if (shown.isEmpty()) graphics.drawString(font,Text.translatable("gui.mtr.image_search_none").getString(),left+34,y+12,0xFF888888,false);
		for (int row=0;row<rows && dropdownScroll+row<shown.size();row++) {
			int index=dropdownScroll+row, rowY=y+row*32;
			if ((mouseX!=previousMouseX || mouseY!=previousMouseY) && mouseX>=left && mouseX<left+298 && mouseY>=rowY && mouseY<rowY+32) highlighted=index;
			if(index==highlighted) graphics.fill(left,rowY,left+298,rowY+32,0xFF445566);
			String name = shown.get(index)==null ? Text.translatable("gui.mtr.image_vanilla").getString() : shown.get(index).name;
			if(shown.get(index)!=null) {
				ImageDatabase.Entry entry=shown.get(index);
				ResourceLocation texture=ImportedImages.texture(entry.hash);
				if(texture!=null) {
					double scale=Math.min(26.0/entry.width,26.0/entry.height);
					int w=Math.max(1,(int)(entry.width*scale)),h=Math.max(1,(int)(entry.height*scale));
					graphics.blit(texture,left+3+(26-w)/2,rowY+(32-h)/2,w,h,0,0,entry.width,entry.height,entry.width,entry.height);
				}
			}
			graphics.drawString(font,font.plainSubstrByWidth(name,256),left+34,rowY+12,0xFFFFFF,false);
		}
		if(shown.size()>rows) {
			int thumb=Math.max(8,rows*32*rows/shown.size()), offset=(rows*32-thumb)*dropdownScroll/(shown.size()-rows);
			graphics.fill(left+294,y+offset,left+297,y+offset+thumb,0xFFBBBBBB);
		}
		graphics.flush(); matrices.popPose();
		previousMouseX=mouseX; previousMouseY=mouseY;
	}
	@Override public boolean isPauseScreen() { return false; }
}
