package mtr.screen;

import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientEditing;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.NameColorDataBase;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import net.minecraft.network.chat.Component;

public abstract class EditNameColorScreenBase<T extends NameColorDataBase> extends ScreenBase implements IGui, IPacket {

	private int nameStart;
	private int colorStart;
	private int colorEnd;

	protected final T data;
	protected final DashboardScreen dashboardScreen;
	private final Component nameText;
	private final Component colorText;

	private final WidgetBetterTextField textFieldName;
	private final WidgetColorSelector colorSelector;

	public EditNameColorScreenBase(T data, DashboardScreen dashboardScreen, String nameKey, String colorKey) {
		super(Text.literal(""));
		this.data = data;
		this.dashboardScreen = dashboardScreen;
		nameText = Text.translatable(nameKey);
		colorText = Text.translatable(colorKey);

		ClientEditing.beginEdit(data);
		textFieldName = new WidgetBetterTextField("");
		colorSelector = new WidgetColorSelector(this, true, () -> {
		});
	}

	@Override
	public void tick() {
		textFieldName.tick();
	}

	@Override
	public void onClose() {
		super.onClose();
		if (minecraft != null) {
			UtilitiesClient.setScreen(minecraft, dashboardScreen);
		}
		saveData();
		ClientEditing.endEdit(data);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	protected void setPositionsAndInit(int nameStart, int colorStart, int colorEnd) {
		this.nameStart = nameStart;
		this.colorStart = colorStart;
		this.colorEnd = colorEnd;

		super.init();
		final int yStart = SQUARE_SIZE + TEXT_FIELD_PADDING / 2;
		IDrawing.setPositionAndWidth(textFieldName, nameStart + TEXT_FIELD_PADDING / 2, yStart, colorStart - nameStart - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(colorSelector, colorStart + TEXT_FIELD_PADDING / 2, yStart, colorEnd - colorStart - TEXT_FIELD_PADDING);

		textFieldName.setValue(data.name);
		colorSelector.setColor(getEditedColor());

		addDrawableChild(textFieldName);
		addDrawableChild(colorSelector);
	}

	protected void renderTextFields(PoseStack matrices) {
		renderEditors(matrices, font, data, height);
		GuiCompat.drawCenteredString(matrices, font, nameText, (nameStart + colorStart) / 2, TEXT_PADDING, ARGB_WHITE);
		GuiCompat.drawCenteredString(matrices, font, colorText, (colorStart + colorEnd) / 2, TEXT_PADDING, ARGB_WHITE);
	}

	protected void saveData() {
		data.name = IGui.textOrUntitled(textFieldName.getValue());
		setEditedColor(colorSelector.getColor());
	}

	protected int getEditedColor() {
		return data.color;
	}

	protected void setEditedColor(int color) {
		data.color = color;
	}

	/**
	 * Shows who else is editing the same thing (multiplayer edit conflicts).
	 */
	public static void renderEditors(PoseStack matrices, net.minecraft.client.gui.Font font, NameColorDataBase data, int height) {
		final java.util.List<String> editors = ClientEditing.getEditors(data);
		if (!editors.isEmpty()) {
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.others_editing", String.join(", ", editors)), SQUARE_SIZE, height - SQUARE_SIZE, 0xFFFFFF55);
		}
	}
}
