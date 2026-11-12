package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.audio.SpeakerSettings;
import mtr.block.BlockAudioSpeaker;
import mtr.client.AudioCache;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.DataConverter;
import mtr.data.NameColorDataBase;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A speaker's settings, opened with the brush. Since 0.70.0 the sound is a list of clips played one after another with a
 * gap after each ({@link ClipList}), and the interval is in seconds to a tenth: in a loop the silence after the last clip,
 * otherwise how soon the speaker may be set off again.
 */
public final class AudioSpeakerScreen extends ScreenBase {
	private final ClientLevel world;
	private final BlockPos pos;
	private final SpeakerSettings initialSettings;
	private final WidgetBetterTextField radius, centerX, centerY, centerZ, triggerRadius;
	private final Button modeButton, soundButton, directionButton, appearanceButton;
	private SpeakerSettings.Mode mode;
	private int audienceMask;
	/** The clips being edited, in order: each one's sound and the gap after it as typed, in seconds */
	private final List<EditedClip> clips = new ArrayList<>();
	private ResourceLocation appearance;
	private boolean invalid;
	private int left, top, panelWidth;
	private boolean finishBeforeRepeat;
	private final WidgetBetterTextField interval;
	private final Button repeatButton;

	public AudioSpeakerScreen(BlockPos pos, SpeakerSettings settings) {
		super(Text.translatable("block.mtr.audio_speaker"));
		world = Minecraft.getInstance().level;
		this.pos = pos;
		initialSettings = settings;
		finishBeforeRepeat = settings.finishBeforeRepeat;
		interval = seconds(settings.intervalTenths);
		repeatButton = UtilitiesClient.newButton(repeatLabel(), button -> { finishBeforeRepeat = !finishBeforeRepeat; button.setMessage(repeatLabel()); });
		settings.clips.forEach(clip -> clips.add(new EditedClip(clip.sound, secondsText(clip.gapTenths))));
		mode = settings.mode;
		audienceMask = settings.audienceMask;
		appearance = settings.appearance;
		radius = number(settings.radius, "", 3);
		centerX = number(settings.triggerCenter.getX(), "X", 9);
		centerY = number(settings.triggerCenter.getY(), "Y", 9);
		centerZ = number(settings.triggerCenter.getZ(), "Z", 9);
		triggerRadius = number(settings.triggerRadius, "", 3);
		modeButton = UtilitiesClient.newButton(modeLabel(), button -> {
			mode = SpeakerSettings.Mode.values()[(mode.ordinal() + 1) % SpeakerSettings.Mode.values().length];
			button.setMessage(modeLabel());
			updateRegionFields();
		});
		soundButton = UtilitiesClient.newButton(soundLabel(), button -> minecraft.setScreen(new ClipList()));
		directionButton = UtilitiesClient.newButton(directionLabel(), button -> minecraft.setScreen(new DirectionPicker()));
		appearanceButton = UtilitiesClient.newButton(appearanceLabel(), button -> chooseAppearance());
	}

	@Override
	protected void init() {
		panelWidth = Math.min(width - 20, 320);
		left = (width - panelWidth) / 2;
		top = Math.max(18, (height - 266) / 2);
		place(soundButton, left, top + 4, panelWidth);
		place(modeButton, left, top + 26, panelWidth);
		place(radius, left + panelWidth - 54, top + 48, 54);
		place(directionButton, left, top + 70, panelWidth);
		final int coordinateWidth = (panelWidth - 8) / 3;
		place(centerX, left, top + 106, coordinateWidth);
		place(centerY, left + coordinateWidth + 4, top + 106, coordinateWidth);
		place(centerZ, left + 2 * (coordinateWidth + 4), top + 106, coordinateWidth);
		place(triggerRadius, left + panelWidth - 54, top + 128, 54);
		place(appearanceButton, left + 22, top + 150, panelWidth - 22);
		place(UtilitiesClient.newButton(Text.translatable("gui.done"), button -> apply()), left, top + 216, panelWidth / 2 - 2);
		place(UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose()), left + panelWidth / 2 + 2, top + 216, panelWidth / 2 - 2);
		place(repeatButton, left, top + 172, panelWidth);
		place(interval, left + panelWidth - 54, top + 194, 54);
		updateRegionFields();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices);
		GuiCompat.drawCenteredString(matrices, font, title, width / 2, top - 13, 0xFFFFFF);
		GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.audio_speaker_radius"), left, top + 54, 0xFFFFFF);
		if (mode == SpeakerSettings.Mode.REGION) {
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.audio_speaker_center"), left, top + 94, 0xFFFFFF);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.audio_speaker_trigger_radius"), left, top + 134, 0xFFFFFF);
		}
		GuiCompat.draw(matrices, font, Text.translatable(mode == SpeakerSettings.Mode.LOOP ? "gui.mtr.audio_speaker_loop_interval" : "gui.mtr.audio_speaker_interval"), left, top + 200, 0xFFFFFF);
		final Block block = BuiltInRegistries.BLOCK.get(appearance);
		GuiCompat.graphics(matrices).renderItem(new ItemStack(block), left + 2, top + 152);
		if (invalid) {
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.audio_speaker_invalid"), width / 2, top + 240, 0xFF7777);
		}
		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Opens the list of uploaded sounds to choose from, coming back to the screen given */
	private void chooseSound(ScreenBase back, Consumer<String> chosen) {
		final List<Choice> choices = new ArrayList<>();
		AudioCache.ENTRIES.stream().filter(entry -> entry.getSoundLocation() != null).map(AudioCache.Entry::getReference)
				.sorted(String.CASE_INSENSITIVE_ORDER).forEach(reference -> choices.add(new Choice(reference, reference)));
		minecraft.setScreen(new Picker(Text.translatable("gui.mtr.audio_speaker_choose_sound"), choices, chosen, back));
	}

	private void chooseAppearance() {
		final List<Choice> choices = new ArrayList<>();
		BuiltInRegistries.BLOCK.keySet().forEach(id -> {
			if (BlockAudioSpeaker.appearance(id) != null) {
				final String label = BuiltInRegistries.BLOCK.get(id).getName().getString();
				choices.add(new Choice(id.toString(), label + "  [" + id + "]"));
			}
		});
		choices.sort(Comparator.comparing(choice -> choice.label, String.CASE_INSENSITIVE_ORDER));
		minecraft.setScreen(new Picker(Text.translatable("gui.mtr.audio_speaker_choose_appearance"), choices, value -> {
			appearance = new ResourceLocation(value);
			appearanceButton.setMessage(appearanceLabel());
		}, this));
	}

	private void apply() {
		if (minecraft.level != world || world == null) {
			onClose();
			return;
		}
		try {
			final boolean region = mode == SpeakerSettings.Mode.REGION;
			final BlockPos center = region ? new BlockPos(value(centerX), value(centerY), value(centerZ)) : initialSettings.triggerCenter;
			if (region && !world.isInWorldBounds(center)) throw new IllegalArgumentException();
			final int regionRadius = region ? value(triggerRadius) : initialSettings.triggerRadius;
			final List<SpeakerSettings.Clip> chosen = new ArrayList<>();
			for (final EditedClip clip : clips) chosen.add(new SpeakerSettings.Clip(clip.sound, tenths(clip.gap)));
			final SpeakerSettings settings = new SpeakerSettings(chosen, mode, value(radius), center, regionRadius, appearance, audienceMask, finishBeforeRepeat, tenths(interval.getValue()));
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeBlockPos(pos);
			settings.write(packet);
			RegistryClient.sendToServer(IPacket.PACKET_UPDATE_AUDIO_SPEAKER, packet);
			onClose();
		} catch (IllegalArgumentException e) {
			invalid = true;
		}
	}

	private Component repeatLabel() {
		return Text.translatable(finishBeforeRepeat ? "gui.mtr.audio_speaker_finish" : "gui.mtr.audio_speaker_interrupt");
	}

	private Component soundLabel() {
		if (clips.isEmpty()) return Text.translatable("gui.mtr.audio_speaker_sound", Text.translatable("gui.mtr.audio_speaker_no_sound"));
		if (clips.size() == 1) return Text.translatable("gui.mtr.audio_speaker_sound", Text.literal(clips.get(0).sound));
		return Text.translatable("gui.mtr.audio_speaker_sounds", Text.literal(clips.get(0).sound), clips.size());
	}

	private Component appearanceLabel() {
		return Text.translatable("gui.mtr.audio_speaker_appearance", BuiltInRegistries.BLOCK.get(appearance).getName());
	}

	private Component directionLabel() {
		final String label;
		if (audienceMask == SpeakerSettings.ALL_DIRECTIONS) {
			label = Text.translatable("gui.mtr.audio_speaker_direction.all").getString();
		} else if (audienceMask == 0) {
			label = Text.translatable("gui.mtr.audio_speaker_direction.none").getString();
		} else {
			final List<String> names = new ArrayList<>();
			for (final Direction face : Direction.values()) {
				if ((audienceMask & SpeakerSettings.directionBit(face)) != 0) names.add(faceLabel(face).getString());
			}
			label = String.join(", ", names);
		}
		return Text.translatable("gui.mtr.audio_speaker_direction", Text.literal(label));
	}

	private static Component faceLabel(Direction face) {
		return Text.translatable("gui.mtr.audio_speaker_direction." + face.name().toLowerCase(Locale.ROOT));
	}

	private Component modeLabel() {
		switch (mode) {
			case LOOP: return Text.translatable("gui.mtr.audio_speaker_loop");
			case REGION: return Text.translatable("gui.mtr.audio_speaker_region");
			default: return Text.translatable("gui.mtr.audio_speaker_redstone");
		}
	}

	private void updateRegionFields() {
		// A loop's interval is the silence after its last clip, so it stays editable; repeating applies only to a play set
		// off (before 0.70.0 the interval was locked in a loop)
		repeatButton.active = mode != SpeakerSettings.Mode.LOOP;
		centerX.visible = centerY.visible = centerZ.visible = triggerRadius.visible = mode == SpeakerSettings.Mode.REGION;
	}

	private void place(AbstractWidget widget, int x, int y, int width) {
		IDrawing.setPositionAndWidth(widget, x, y, width);
		// Values are assigned before layout, when EditBox has no visible text width yet.
		if (widget instanceof WidgetBetterTextField) ((WidgetBetterTextField) widget).moveCursorToStart();
		addDrawableChild(widget);
	}

	private static WidgetBetterTextField number(int value, String hint, int length) {
		final WidgetBetterTextField field = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, hint, length);
		field.setValue(Integer.toString(value));
		return field;
	}

	private static int value(WidgetBetterTextField field) {
		return Integer.parseInt(field.getValue());
	}

	/** A field for seconds to a tenth */
	private static WidgetBetterTextField seconds(int tenths) {
		final WidgetBetterTextField field = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "0", 6);
		field.setValue(secondsText(tenths));
		return field;
	}

	public static String secondsText(int tenths) {
		return tenths % 10 == 0 ? Integer.toString(tenths / 10) : String.format(Locale.ROOT, "%d.%d", tenths / 10, tenths % 10);
	}

	/**
	 * @return seconds as typed, in tenths, rounded; empty is 0
	 * @throws IllegalArgumentException when not a number or out of range
	 */
	public static int tenths(String text) {
		if (text.trim().isEmpty()) return 0;
		final double seconds;
		try {
			seconds = Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(e);
		}
		final long tenths = Math.round(seconds * 10);
		if (!(seconds >= 0) || tenths > SpeakerSettings.MAX_TENTHS) throw new IllegalArgumentException("Out of range");
		return (int) tenths;
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
	 * The clips one after another (0.70.0): each one's sound and the gap after it in seconds (none after the last: that is
	 * the interval), moved up or down or taken out; more added from the uploaded sounds. Changes stay with the speaker's
	 * screen until it is done.
	 */
	private final class ClipList extends ScreenBase {
		private static final int ROW = 22;
		private int page;
		private boolean invalidGap;

		private ClipList() {
			super(Text.translatable("gui.mtr.audio_speaker_clips"));
		}

		private int rowsPerPage() {
			return Math.max(1, (height - 100) / ROW);
		}

		@Override
		protected void init() {
			final int panel = Math.min(width - 20, 360);
			final int listLeft = (width - panel) / 2;
			final int listTop = 36;
			final int perPage = rowsPerPage();
			page = Math.max(0, Math.min(page, Math.max(0, clips.size() - 1) / perPage));
			for (int row = 0; row < perPage; row++) {
				final int index = page * perPage + row;
				if (index >= clips.size()) break;
				final EditedClip clip = clips.get(index);
				final int y = listTop + row * ROW;
				final Button sound = UtilitiesClient.newButton(Text.literal((index + 1) + ". " + clip.sound), button -> chooseSound(this, value -> clip.sound = value));
				IDrawing.setPositionAndWidth(sound, listLeft, y, panel - 150);
				addDrawableChild(sound);
				if (index < clips.size() - 1) {
					final WidgetBetterTextField gap = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "0", 6);
					gap.setValue(clip.gap);
					gap.setResponder(text -> clip.gap = text);
					IDrawing.setPositionAndWidth(gap, listLeft + panel - 146, y, 50);
					gap.moveCursorToStart();
					addDrawableChild(gap);
				}
				addSmall("↑", listLeft + panel - 92, y, index > 0, () -> swap(index, index - 1));
				addSmall("↓", listLeft + panel - 62, y, index < clips.size() - 1, () -> swap(index, index + 1));
				addSmall("×", listLeft + panel - 32, y, true, () -> clips.remove(index));
			}
			final int bottom = height - 30;
			if (clips.size() > perPage) {
				addSmall("<", listLeft, bottom - 24, page > 0, () -> page--);
				addSmall(">", listLeft + panel - 26, bottom - 24, (page + 1) * perPage < clips.size(), () -> page++);
			}
			final Button add = UtilitiesClient.newButton(Text.translatable("gui.mtr.audio_speaker_add_clip"), button -> chooseSound(this, value -> {
				clips.add(new EditedClip(value, "0"));
				page = (clips.size() - 1) / rowsPerPage();
			}));
			add.active = clips.size() < SpeakerSettings.MAX_CLIPS;
			IDrawing.setPositionAndWidth(add, listLeft + 30, bottom - 24, panel - 60);
			addDrawableChild(add);
			final Button done = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> {
				try {
					clips.forEach(clip -> tenths(clip.gap));
					onClose();
				} catch (IllegalArgumentException e) {
					invalidGap = true;
				}
			});
			IDrawing.setPositionAndWidth(done, listLeft, bottom, panel);
			addDrawableChild(done);
		}

		private void addSmall(String label, int x, int y, boolean active, Runnable action) {
			final Button button = UtilitiesClient.newButton(Text.literal(label), pressed -> {
				action.run();
				init(minecraft, width, height);
			});
			button.active = active;
			IDrawing.setPositionAndWidth(button, x, y, 26);
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
			GuiCompat.drawCenteredString(matrices, font, Text.translatable(clips.isEmpty() ? "gui.mtr.audio_speaker_no_clips" : "gui.mtr.audio_speaker_clips_help"), width / 2, 22, 0xCCCCCC);
			if (invalidGap) {
				GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.audio_speaker_invalid_gap"), width / 2, height - 42, 0xFF7777);
			}
			super.render(matrices, mouseX, mouseY, delta);
		}

		@Override
		public void onClose() {
			soundButton.setMessage(soundLabel());
			minecraft.setScreen(AudioSpeakerScreen.this);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}

	private final class DirectionPicker extends ScreenBase {
		private int selectedMask = audienceMask;

		private DirectionPicker() {
			super(Text.translatable("gui.mtr.audio_speaker_choose_directions"));
		}

		@Override
		protected void init() {
			final Direction[] faces = Direction.values();
			final WidgetBetterCheckbox[] checkboxes = new WidgetBetterCheckbox[faces.length];
			final int panel = Math.min(width - 20, 260);
			final int left = (width - panel) / 2;
			final int top = Math.max(44, (height - 114) / 2);
			for (int i = 0; i < faces.length; i++) {
				final Direction face = faces[i];
				final int bit = SpeakerSettings.directionBit(face);
				final WidgetBetterCheckbox checkbox = new WidgetBetterCheckbox(0, 0, 0, 20, faceLabel(face),
						checked -> selectedMask = checked ? selectedMask | bit : selectedMask & ~bit);
				checkbox.setChecked((selectedMask & bit) != 0);
				checkboxes[i] = checkbox;
				IDrawing.setPositionAndWidth(checkbox, left + i % 2 * panel / 2, top + i / 2 * 22, panel / 2);
				addDrawableChild(checkbox);
			}
			final Button all = UtilitiesClient.newButton(Text.translatable("gui.mtr.audio_speaker_direction.all"), button -> {
				selectedMask = SpeakerSettings.ALL_DIRECTIONS;
				for (final WidgetBetterCheckbox checkbox : checkboxes) checkbox.setChecked(true);
			});
			IDrawing.setPositionAndWidth(all, left, top + 66, panel);
			addDrawableChild(all);
			final Button done = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> {
				audienceMask = selectedMask;
				directionButton.setMessage(directionLabel());
				onClose();
			});
			IDrawing.setPositionAndWidth(done, left, top + 90, panel / 2 - 2);
			addDrawableChild(done);
			final Button cancel = UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose());
			IDrawing.setPositionAndWidth(cancel, left + panel / 2 + 2, top + 90, panel / 2 - 2);
			addDrawableChild(cancel);
		}

		@Override
		public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
			renderBackground(matrices);
			GuiCompat.drawCenteredString(matrices, font, title, width / 2, 8, 0xFFFFFF);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.audio_speaker_direction_help"), width / 2, 22, 0xCCCCCC);
			super.render(matrices, mouseX, mouseY, delta);
		}

		@Override
		public void onClose() {
			minecraft.setScreen(AudioSpeakerScreen.this);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}

	private static final class Choice {
		private final String value, label;

		private Choice(String value, String label) {
			this.value = value;
			this.label = label;
		}
	}

	private final class Picker extends ScreenBase {
		private final List<DataConverter> rows = new ArrayList<>();
		private final DashboardList list;
		private final ScreenBase back;
		private String search = "";

		private Picker(Component title, List<Choice> choices, Consumer<String> select, ScreenBase back) {
			super(title);
			this.back = back;
			list = new DashboardList(null, null, null, null, null, null, null, () -> search, value -> search = value, false);
			final Map<NameColorDataBase, String> values = new IdentityHashMap<>();
			for (final Choice choice : choices) {
				final DataConverter row = new DataConverter(choice.label, 0xEEEEEE);
				rows.add(row);
				values.put(row, choice.value);
			}
			list.setRowStyle(row -> row.name, row -> true, row -> row.color, row -> {
				// Database IDs are random (including a requested ID of zero), never list offsets.
				if (!values.containsKey(row)) return;
				select.accept(values.get(row));
				onClose();
			});
		}

		@Override
		protected void init() {
			list.x = Math.max(8, (width - 420) / 2);
			list.y = 22;
			list.width = width - list.x * 2;
			list.height = height - 52;
			list.init(this::addDrawableChild);
			list.setData(rows, false, false, false, false, false, false);
			final Button backButton = UtilitiesClient.newButton(Text.translatable("gui.back"), button -> onClose());
			IDrawing.setPositionAndWidth(backButton, width / 2 - 50, height - 24, 100);
			addDrawableChild(backButton);
		}

		@Override
		public void tick() {
			list.tick();
		}

		@Override
		public boolean mouseClicked(double x, double y, int button) {
			return button == 0 && list.mouseClicked(x, y) || super.mouseClicked(x, y, button);
		}

		@Override
		public boolean mouseScrolled(double x, double y, double amount) {
			list.mouseScrolled(x, y, amount);
			return true;
		}

		@Override
		public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
			renderBackground(matrices);
			GuiCompat.drawCenteredString(matrices, font, title, width / 2, 8, 0xFFFFFF);
			list.render(matrices, font);
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
}
