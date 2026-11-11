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

public final class AudioSpeakerScreen extends ScreenBase {
	private final ClientLevel world;
	private final BlockPos pos;
	private final SpeakerSettings initialSettings;
	private final WidgetBetterTextField radius, centerX, centerY, centerZ, triggerRadius;
	private final Button modeButton, soundButton, directionButton, appearanceButton;
	private SpeakerSettings.Mode mode;
	private int audienceMask;
	private String sound;
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
		interval = number(settings.intervalSeconds, "", 4);
		repeatButton = UtilitiesClient.newButton(repeatLabel(), button -> { finishBeforeRepeat = !finishBeforeRepeat; button.setMessage(repeatLabel()); });
		sound = settings.sound;
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
		soundButton = UtilitiesClient.newButton(soundLabel(), button -> chooseSound());
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
		GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.audio_speaker_interval"), left, top + 200, 0xFFFFFF);
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

	private void chooseSound() {
		final List<Choice> choices = new ArrayList<>();
		choices.add(new Choice("", Text.translatable("gui.mtr.audio_speaker_no_sound").getString()));
		AudioCache.ENTRIES.stream().filter(entry -> entry.getSoundLocation() != null).map(AudioCache.Entry::getReference)
				.sorted(String.CASE_INSENSITIVE_ORDER).forEach(reference -> choices.add(new Choice(reference, reference)));
		minecraft.setScreen(new Picker(Text.translatable("gui.mtr.audio_speaker_choose_sound"), choices, value -> {
			sound = value;
			soundButton.setMessage(soundLabel());
		}));
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
		}));
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
			final SpeakerSettings settings = new SpeakerSettings(sound, mode, value(radius), center, regionRadius, appearance, audienceMask, finishBeforeRepeat, value(interval));
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
		return Text.translatable("gui.mtr.audio_speaker_sound", sound.isEmpty() ? Text.translatable("gui.mtr.audio_speaker_no_sound") : Text.literal(sound));
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
		repeatButton.active = interval.active = mode != SpeakerSettings.Mode.LOOP;
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
		private String search = "";

		private Picker(Component title, List<Choice> choices, Consumer<String> select) {
			super(title);
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
			final Button back = UtilitiesClient.newButton(Text.translatable("gui.back"), button -> onClose());
			IDrawing.setPositionAndWidth(back, width / 2 - 50, height - 24, 100);
			addDrawableChild(back);
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
			minecraft.setScreen(AudioSpeakerScreen.this);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}
}
