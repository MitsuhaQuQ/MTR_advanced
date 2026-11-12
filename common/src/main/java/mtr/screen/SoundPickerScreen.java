package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.IGui;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A whole screen of {@link SoundTree} (0.73.1): taking a sound gives it to the caller and goes back to the screen given.
 * The audio speaker's clips and the announcer's clips choose their sounds here.
 */
public final class SoundPickerScreen extends ScreenBase implements IGui {

	private final ScreenBase back;
	private final SoundTree tree;

	/**
	 * @param withGameSounds whether the game's sounds are listed besides the uploaded files
	 * @param preview        plays a sound, or null if clicking a sound takes it
	 * @param chosen         gets the sound taken
	 */
	public SoundPickerScreen(Component title, ScreenBase back, boolean withGameSounds, Consumer<String> preview, Consumer<String> chosen) {
		super(title);
		this.back = back;
		tree = new SoundTree(withGameSounds, preview, value -> {
			chosen.accept(value);
			onClose();
		});
	}

	@Override
	protected void init() {
		final DashboardList list = tree.list;
		list.x = Math.max(8, (width - 420) / 2);
		list.y = 22;
		list.width = width - list.x * 2;
		list.height = height - 52;
		list.init(this::addDrawableChild);
		final Button backButton = UtilitiesClient.newButton(Text.translatable("gui.back"), button -> onClose());
		IDrawing.setPositionAndWidth(backButton, width / 2 - 50, height - 24, 100);
		addDrawableChild(backButton);
	}

	@Override
	public void tick() {
		tree.tick();
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		return button == 0 && tree.list.mouseClicked(x, y) || super.mouseClicked(x, y, button);
	}

	@Override
	public void mouseMoved(double x, double y) {
		tree.list.mouseMoved(x, y);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double amount) {
		tree.list.mouseScrolled(x, y, amount);
		return true;
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices);
		final String path = tree.path();
		GuiCompat.drawCenteredString(matrices, font, path.isEmpty() ? title : Text.literal(title.getString() + "  —  " + path), width / 2, 8, 0xFFFFFF);
		GuiCompat.fill(matrices, tree.list.x, tree.list.y, tree.list.x + tree.list.width, tree.list.y + tree.list.height, ARGB_BACKGROUND);
		tree.list.render(matrices, font);
		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(back);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
