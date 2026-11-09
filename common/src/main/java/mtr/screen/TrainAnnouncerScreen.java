package mtr.screen;

import mtr.compat.GuiCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.audio.AnnouncementSound;
import mtr.block.BlockTrainAnnouncer;
import mtr.client.AnnouncementPlayer;
import mtr.client.AudioCache;
import mtr.client.IDrawing;
import mtr.data.DataConverter;
import mtr.data.RailwayData;
import mtr.data.StationCall;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TrainAnnouncerScreen extends TrainSensorScreenBase {

	private final String initialMessage;
	private final String initialSoundIdString;
	private int seconds = 5;
	private StationCall.Trigger trigger = StationCall.Trigger.LEGACY;
	private boolean toRiders;
	private final DashboardList availableSoundsList;
	private final Map<String, String> uploadedSounds = new HashMap<>();

	private static final int MAX_MESSAGE_LENGTH = 256;
	public TrainAnnouncerScreen(BlockPos pos) {
		super(pos, true, true,
				new Tuple<>(new WidgetBetterTextField("", MAX_MESSAGE_LENGTH), Text.translatable("gui.mtr.announcement_message")),
				new Tuple<>(new WidgetBetterTextField("", AnnouncementSound.MAX_LENGTH), Text.translatable("gui.mtr.sound_file")),
				new Tuple<>(new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, "", 5), Text.translatable("gui.mtr.station_trigger_seconds"))
		);

		minecraft = Minecraft.getInstance();
		final ClientLevel world = minecraft.level;
		if (world != null) {
			final BlockEntity entity = world.getBlockEntity(pos);
			if (entity instanceof BlockTrainAnnouncer.TileEntityTrainAnnouncer) {
				final BlockTrainAnnouncer.TileEntityTrainAnnouncer announcer = (BlockTrainAnnouncer.TileEntityTrainAnnouncer) entity;
				initialMessage = announcer.getMessage();
				initialSoundIdString = announcer.getSoundIdString();
				trigger = announcer.getTrigger();
				seconds = trigger == StationCall.Trigger.LEGACY && announcer.getSeconds() == 0 ? 5 : announcer.getSeconds();
				toRiders = announcer.getToRiders();
			} else {
				initialMessage = "";
				initialSoundIdString = "";
			}
		} else {
			initialMessage = "";
			initialSoundIdString = "";
		}

		availableSoundsList = new DashboardList((data, color) -> {
			if (world != null && minecraft.player != null) {
				AnnouncementPlayer.play(getSoundString(data.name), pos);
			}
		}, null, null, null, (data, color) -> {
			textFields[1].setValue(getSoundString(data.name));
			setListVisibility(false);
		}, null, null, () -> "", text -> {
		}, false);
		// The server's own files come first, by name; the field gets what to save
		final List<DataConverter> sounds = new ArrayList<>();
		AudioCache.ENTRIES.stream().sorted(Comparator.comparing(AudioCache.Entry::getReference)).forEach(entry -> {
			// With the sound the game plays it as, which /playsound can name too
			final String label = Text.translatable("gui.mtr.uploaded_audio", entry.getReference()).getString() + (entry.getSoundLocation() == null ? "" : "  " + entry.getSoundLocation());
			uploadedSounds.put(label, entry.getReference());
			sounds.add(new DataConverter(label, ARGB_BACKGROUND));
		});
		sounds.addAll(Minecraft.getInstance().getSoundManager().getAvailableSounds().stream().map(soundId -> new DataConverter(soundId.toString(), ARGB_BACKGROUND)).sorted().collect(Collectors.toList()));
		availableSoundsList.setData(sounds, true, false, false, false, true, false);
	}

	private String getSoundString(String label) {
		return uploadedSounds.getOrDefault(label, label);
	}

	@Override
	protected void init() {
		super.init();
		textFields[0].setValue(initialMessage);
		textFields[1].setValue(initialSoundIdString);
		textFields[2].setValue(String.valueOf(seconds));
		final int optionWidth = (width - SQUARE_SIZE * 3) / 2;
		addTriggerButton(() -> trigger, value -> trigger = value, optionWidth);
		final Button audience = UtilitiesClient.newButton(Text.translatable(audienceKey()), button -> {
			toRiders = !toRiders;
			button.setMessage(Text.translatable(audienceKey()));
		});
		IDrawing.setPositionAndWidth(audience, SQUARE_SIZE * 2 + optionWidth, optionsY, optionWidth);
		addDrawableChild(audience);

		setListVisibility(false);
		availableSoundsList.y = SQUARE_SIZE * 2 + TEXT_HEIGHT + TEXT_PADDING + TEXT_FIELD_PADDING;
		availableSoundsList.height = height - availableSoundsList.y - SQUARE_SIZE;
		availableSoundsList.width = width / 2 - SQUARE_SIZE;
		availableSoundsList.init(this::addDrawableChild);
	}

	private String audienceKey() {
		return toRiders ? "gui.mtr.station_audience_train" : "gui.mtr.station_audience_platform";
	}

	@Override
	protected int getNumber() {
		try {
			return Integer.parseInt(textFields[2].getValue());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	protected String[] getTimingSettings() {
		return new String[]{trigger.name(), Boolean.toString(toRiders)};
	}

	@Override
	public void tick() {
		super.tick();
		availableSoundsList.tick();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
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
}
