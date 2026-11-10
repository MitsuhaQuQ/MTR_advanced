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
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class AudioSpeakerScreen extends ScreenBase {
	private final ClientLevel world;
	private final BlockPos pos;
	private final SpeakerSettings initialSettings;
	private final WidgetBetterTextField radius, centerX, centerY, centerZ, triggerRadius;
	private final Button modeButton, soundButton, appearanceButton;
	private SpeakerSettings.Mode mode;
	private String sound;
	private ResourceLocation appearance;
	private boolean invalid;
	private int left, top, panelWidth;

	public AudioSpeakerScreen(BlockPos pos, SpeakerSettings settings) {
		super(Text.translatable("block.mtr.audio_speaker"));
		world = Minecraft.getInstance().level;
		this.pos = pos;
		initialSettings = settings;
		sound = settings.sound;
		mode = settings.mode;
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
		appearanceButton = UtilitiesClient.newButton(appearanceLabel(), button -> chooseAppearance());
	}

	@Override
	protected void init() {
		panelWidth = Math.min(width - 20, 320);
		left = (width - panelWidth) / 2;
		top = Math.max(18, (height - 206) / 2);
		place(soundButton, left, top + 4, panelWidth);
		place(modeButton, left, top + 28, panelWidth);
		place(radius, left + panelWidth - 54, top + 52, 54);
		final int coordinateWidth = (panelWidth - 8) / 3;
		place(centerX, left, top + 87, coordinateWidth);
		place(centerY, left + coordinateWidth + 4, top + 87, coordinateWidth);
		place(centerZ, left + 2 * (coordinateWidth + 4), top + 87, coordinateWidth);
		place(triggerRadius, left + panelWidth - 54, top + 111, 54);
		place(appearanceButton, left + 22, top + 137, panelWidth - 22);
		place(UtilitiesClient.newButton(Text.translatable("gui.done"), button -> apply()), left, top + 163, panelWidth / 2 - 2);
		place(UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose()), left + panelWidth / 2 + 2, top + 163, panelWidth / 2 - 2);
		updateRegionFields();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices);
		GuiCompat.drawCenteredString(matrices, font, title, width / 2, top - 13, 0xFFFFFF);
		GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.audio_speaker_radius"), left, top + 58, 0xFFFFFF);
		if (mode == SpeakerSettings.Mode.REGION) {
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.audio_speaker_center"), left, top + 75, 0xFFFFFF);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.audio_speaker_trigger_radius"), left, top + 117, 0xFFFFFF);
		}
		final Block block = BuiltInRegistries.BLOCK.get(appearance);
		GuiCompat.graphics(matrices).renderItem(new ItemStack(block), left + 2, top + 139);
		if (invalid) {
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.audio_speaker_invalid"), width / 2, top + 189, 0xFF7777);
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
			final SpeakerSettings settings = new SpeakerSettings(sound, mode, value(radius), center, regionRadius, appearance);
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeBlockPos(pos);
			settings.write(packet);
			RegistryClient.sendToServer(IPacket.PACKET_UPDATE_AUDIO_SPEAKER, packet);
			onClose();
		} catch (IllegalArgumentException e) {
			invalid = true;
		}
	}

	private Component soundLabel() {
		return Text.translatable("gui.mtr.audio_speaker_sound", sound.isEmpty() ? Text.translatable("gui.mtr.audio_speaker_no_sound") : Text.literal(sound));
	}

	private Component appearanceLabel() {
		return Text.translatable("gui.mtr.audio_speaker_appearance", BuiltInRegistries.BLOCK.get(appearance).getName());
	}

	private Component modeLabel() {
		switch (mode) {
			case LOOP: return Text.translatable("gui.mtr.audio_speaker_loop");
			case REGION: return Text.translatable("gui.mtr.audio_speaker_region");
			default: return Text.translatable("gui.mtr.audio_speaker_redstone");
		}
	}

	private void updateRegionFields() {
		centerX.visible = centerY.visible = centerZ.visible = triggerRadius.visible = mode == SpeakerSettings.Mode.REGION;
	}

	private void place(AbstractWidget widget, int x, int y, int width) {
		IDrawing.setPositionAndWidth(widget, x, y, width);
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
			for (int i = 0; i < choices.size(); i++) rows.add(new DataConverter(i, choices.get(i).label, 0xEEEEEE));
			list.setRowStyle(row -> row.name, row -> true, row -> row.color, row -> {
				select.accept(choices.get((int) row.id).value);
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
