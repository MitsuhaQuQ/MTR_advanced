package mtr.screen;

import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.audio.AnnouncementClips;
import mtr.audio.AnnouncementSound;
import mtr.block.BlockTrainAnnouncer;
import mtr.client.AnnouncementPlayer;
import mtr.client.IDrawing;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * An announcer's settings: its message and sounds, and the routes and speeds of the trains whose riders hear it. Since
 * 0.72.0 it plays several sounds one after another with a gap after each, as the audio speaker does: the sound field is
 * the first, and "拼接…" edits the whole list ({@link ClipList}). The station triggers and the platform audience are gone
 * (announcers are for riders going over them only, as in MTR).
 */
public class TrainAnnouncerScreen extends TrainSensorScreenBase {

	private String initialMessage;
	/** The sounds beside the sound field, as a tree (0.73.1) */
	private final SoundTree soundTree;
	private final DashboardList availableSoundsList;
	/** The sounds being edited, in order: each one's sound and the gap after it as typed, in seconds */
	private final List<EditedClip> clips = new ArrayList<>();

	private static final int MAX_MESSAGE_LENGTH = 256;

	public TrainAnnouncerScreen(BlockPos pos) {
		super(pos, true, false,
				new Tuple<>(new WidgetBetterTextField("", MAX_MESSAGE_LENGTH), Text.translatable("gui.mtr.announcement_message")),
				new Tuple<>(new WidgetBetterTextField("", AnnouncementSound.MAX_LENGTH), Text.translatable("gui.mtr.sound_file"))
		);

		minecraft = Minecraft.getInstance();
		final ClientLevel world = minecraft.level;
		initialMessage = "";
		final BlockEntity entity = world == null ? null : world.getBlockEntity(pos);
		if (entity instanceof BlockTrainAnnouncer.TileEntityTrainAnnouncer) {
			final BlockTrainAnnouncer.TileEntityTrainAnnouncer announcer = (BlockTrainAnnouncer.TileEntityTrainAnnouncer) entity;
			initialMessage = announcer.getMessage();
			announcer.getClips().forEach(clip -> clips.add(new EditedClip(clip.sound, AudioSpeakerScreen.secondsText(clip.gapTenths))));
		}

		// Clicking plays a sound, the add button puts it in the field
		soundTree = new SoundTree(true, sound -> {
			if (world != null && minecraft.player != null) AnnouncementPlayer.play(sound, pos);
		}, sound -> {
			textFields[1].setValue(sound);
			setListVisibility(false);
		});
		availableSoundsList = soundTree.list;
	}

	@Override
	protected void init() {
		super.init();
		textFields[0].setValue(initialMessage);
		textFields[1].setValue(clips.isEmpty() ? "" : clips.get(0).sound);
		final Button clipsButton = UtilitiesClient.newButton(Text.translatable("gui.mtr.announcement_clips_button", clips.size()), button -> {
			keepFields();
			minecraft.setScreen(new ClipList());
		});
		// Beside the routes' edit button
		IDrawing.setPositionAndWidth(clipsButton, SQUARE_SIZE + PANEL_WIDTH / 2, optionsY + SQUARE_SIZE * 4, PANEL_WIDTH / 2);
		addDrawableChild(clipsButton);

		setListVisibility(false);
		availableSoundsList.y = SQUARE_SIZE * 2 + TEXT_HEIGHT + TEXT_PADDING + TEXT_FIELD_PADDING;
		availableSoundsList.height = height - availableSoundsList.y - SQUARE_SIZE;
		availableSoundsList.width = width / 2 - SQUARE_SIZE;
		availableSoundsList.init(this::addDrawableChild);
	}

	/** The fields as typed, kept while the list of sounds is open (coming back draws the screen again) */
	private void keepFields() {
		initialMessage = textFields[0].getValue();
		setFirstSound(textFields[1].getValue());
	}

	/** The sound field is the first sound: changing it changes that one, or adds it */
	private void setFirstSound(String sound) {
		final String clean = AnnouncementSound.clean(sound);
		if (clips.isEmpty()) {
			if (!clean.isEmpty()) clips.add(new EditedClip(clean, "0"));
		} else if (clean.isEmpty() && clips.size() == 1) {
			clips.clear();
		} else if (!clean.isEmpty()) {
			clips.get(0).sound = clean;
		}
	}

	/**
	 * The sounds with their gaps (0.72.0), after the message and the first sound; a gap that isn't a number is 0.
	 */
	@Override
	protected String[] getTimingSettings() {
		setFirstSound(textFields[1].getValue());
		final List<AnnouncementClips.Clip> list = new ArrayList<>();
		for (final EditedClip clip : clips) {
			int gap;
			try {
				gap = AudioSpeakerScreen.tenths(clip.gap);
			} catch (IllegalArgumentException e) {
				gap = 0;
			}
			list.add(new AnnouncementClips.Clip(clip.sound, gap));
		}
		return new String[]{AnnouncementClips.toText(list)};
	}

	@Override
	public void tick() {
		super.tick();
		soundTree.tick();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			// A folder or the way back up in the open list
			if (availableSoundsList.x < width && availableSoundsList.mouseClicked(mouseX, mouseY)) return true;
			if (RailwayData.isBetween(mouseX, UtilitiesClient.getWidgetX(textFields[1]), UtilitiesClient.getWidgetX(textFields[1]) + textFields[1].getWidth()) && RailwayData.isBetween(mouseY, UtilitiesClient.getWidgetY(textFields[1]), UtilitiesClient.getWidgetY(textFields[1]) + textFields[1].getHeight())) {
				setListVisibility(true);
			} else if (!RailwayData.isBetween(mouseX, availableSoundsList.x, availableSoundsList.x + availableSoundsList.width) || !RailwayData.isBetween(mouseY, availableSoundsList.y, availableSoundsList.y + availableSoundsList.height)) {
				setListVisibility(false);
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		availableSoundsList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		availableSoundsList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	protected void renderAdditional(PoseStack matrices) {
		GuiCompat.fill(matrices, availableSoundsList.x, availableSoundsList.y, availableSoundsList.x + availableSoundsList.width, availableSoundsList.y + availableSoundsList.height, ARGB_BACKGROUND);
		availableSoundsList.render(matrices, font);
	}

	private void setListVisibility(boolean visible) {
		availableSoundsList.x = visible ? width / 2 : width;
	}

	private static final class EditedClip {
		private String sound;
		private String gap;

		private EditedClip(String sound, String gap) {
			this.sound = sound;
			this.gap = gap;
		}
	}

	/**
	 * The sounds one after another (0.72.0): each one typed or chosen from the list, the gap after it in seconds (none
	 * after the last), moved up or down or taken out, more added. As the audio speaker's list, but any sound an announcer
	 * plays: a sound of the game, a file added to the server or a web address.
	 */
	private final class ClipList extends ScreenBase {
		private static final int ROW = 22;
		private int page;
		private boolean invalidGap;

		private ClipList() {
			super(Text.translatable("gui.mtr.announcement_clips"));
		}

		private int rowsPerPage() {
			return Math.max(1, (height - 100) / ROW);
		}

		@Override
		protected void init() {
			final int panel = Math.min(width - 20, 420);
			final int listLeft = (width - panel) / 2;
			final int listTop = 36;
			final int perPage = rowsPerPage();
			page = Math.max(0, Math.min(page, Math.max(0, clips.size() - 1) / perPage));
			for (int row = 0; row < perPage; row++) {
				final int index = page * perPage + row;
				if (index >= clips.size()) break;
				final EditedClip clip = clips.get(index);
				final int y = listTop + row * ROW;
				final WidgetBetterTextField sound = new WidgetBetterTextField("", AnnouncementSound.MAX_LENGTH);
				sound.setValue(clip.sound);
				sound.setResponder(text -> clip.sound = text);
				IDrawing.setPositionAndWidth(sound, listLeft, y, panel - 206);
				sound.moveCursorToStart();
				addDrawableChild(sound);
				addSmall(Text.translatable("gui.mtr.announcement_choose"), listLeft + panel - 202, y, 50, true, () -> minecraft.setScreen(picker(this, value -> clip.sound = value)));
				if (index < clips.size() - 1) {
					final WidgetBetterTextField gap = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "0", 6);
					gap.setValue(clip.gap);
					gap.setResponder(text -> clip.gap = text);
					IDrawing.setPositionAndWidth(gap, listLeft + panel - 148, y, 50);
					gap.moveCursorToStart();
					addDrawableChild(gap);
				}
				addSmall(Text.literal("↑"), listLeft + panel - 92, y, 26, index > 0, () -> swap(index, index - 1));
				addSmall(Text.literal("↓"), listLeft + panel - 62, y, 26, index < clips.size() - 1, () -> swap(index, index + 1));
				addSmall(Text.literal("×"), listLeft + panel - 32, y, 26, true, () -> clips.remove(index));
			}
			final int bottom = height - 30;
			if (clips.size() > perPage) {
				addSmall(Text.literal("<"), listLeft, bottom - 24, 26, page > 0, () -> page--);
				addSmall(Text.literal(">"), listLeft + panel - 26, bottom - 24, 26, (page + 1) * perPage < clips.size(), () -> page++);
			}
			final Button add = UtilitiesClient.newButton(Text.translatable("gui.mtr.audio_speaker_add_clip"), button -> minecraft.setScreen(picker(this, value -> {
				clips.add(new EditedClip(value, "0"));
				page = (clips.size() - 1) / rowsPerPage();
			})));
			add.active = clips.size() < AnnouncementClips.MAX_CLIPS;
			IDrawing.setPositionAndWidth(add, listLeft + 30, bottom - 24, panel - 60);
			addDrawableChild(add);
			final Button done = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> {
				try {
					clips.forEach(clip -> AudioSpeakerScreen.tenths(clip.gap));
					onClose();
				} catch (IllegalArgumentException e) {
					invalidGap = true;
				}
			});
			IDrawing.setPositionAndWidth(done, listLeft, bottom, panel);
			addDrawableChild(done);
		}

		private void addSmall(Component label, int x, int y, int buttonWidth, boolean active, Runnable action) {
			final Button button = UtilitiesClient.newButton(label, pressed -> {
				action.run();
				if (minecraft.screen == this) init(minecraft, width, height);
			});
			button.active = active;
			IDrawing.setPositionAndWidth(button, x, y, buttonWidth);
			addDrawableChild(button);
		}

		private void swap(int a, int b) {
			final EditedClip first = clips.get(a);
			clips.set(a, clips.get(b));
			clips.set(b, first);
		}

		@Override
		public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
			renderBackground(matrices);
			GuiCompat.drawCenteredString(matrices, font, title, width / 2, 8, 0xFFFFFF);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable(clips.isEmpty() ? "gui.mtr.announcement_no_clips" : "gui.mtr.announcement_clips_help"), width / 2, 22, 0xCCCCCC);
			if (invalidGap) {
				GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.audio_speaker_invalid_gap"), width / 2, height - 42, 0xFF7777);
			}
			super.render(matrices, mouseX, mouseY, delta);
		}

		@Override
		public void onClose() {
			clips.removeIf(clip -> AnnouncementSound.clean(clip.sound).isEmpty());
			minecraft.setScreen(TrainAnnouncerScreen.this);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}

	/** The game's sounds and the uploaded files as a tree (0.73.1); clicking plays one, the add button takes it */
	private SoundPickerScreen picker(ScreenBase back, Consumer<String> chosen) {
		return new SoundPickerScreen(Text.translatable("gui.mtr.sound_file"), back, true, sound -> AnnouncementPlayer.play(sound, pos), chosen);
	}
}
