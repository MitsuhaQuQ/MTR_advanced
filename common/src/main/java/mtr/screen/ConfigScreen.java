package mtr.screen;

import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Keys;
import mtr.Patreon;
import mtr.client.ClientData;
import mtr.client.Config;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.MotionConfig;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class ConfigScreen extends ScreenBase implements IGui {

	private boolean useMTRFont;
	private boolean showAnnouncementMessages;
	private boolean useTTSAnnouncements;
	private boolean hideSpecialRailColors;
	private boolean hideTranslucentParts;
	private boolean shiftToToggleSitting;
	private int languageOptions;
	private boolean useDynamicFPS;

	private final boolean hasTimeAndWindControls;
	private final boolean useTimeAndWindSync;

	private final WidgetBetterCheckbox checkboxUseTimeAndWindSync;
	private final Button buttonUseMTRFont;
	private final Button buttonShowAnnouncementMessages;
	private final Button buttonUseTTSAnnouncements;
	private final Button buttonHideSpecialRailColors;
	private final Button buttonHideTranslucentParts;
	private final Button buttonOptimizeTrainRendering;
	private final Button buttonShowDebugInfo;
	private final Button buttonShiftToToggleSitting;
	private final Button buttonLanguageOptions;
	private final Button buttonUseDynamicFPS;
	private final WidgetShorterSlider sliderTrackTextureOffset;
	private final WidgetShorterSlider sliderDynamicTextureResolution;
	private final WidgetShorterSlider sliderTrainRenderDistanceRatio;
	private final Button buttonSupportPatreon;

	private static final int BUTTON_WIDTH = 60;
	private int buttonX;
	private static final int BUTTON_HEIGHT = TEXT_HEIGHT + TEXT_PADDING;

	public ConfigScreen() {
		this(false, false);
	}

	public ConfigScreen(boolean useTimeAndWindSync) {
		this(true, useTimeAndWindSync);
	}

	private ConfigScreen(boolean hasTimeAndWindControls, boolean useTimeAndWindSync) {
		super(Text.literal(""));

		this.hasTimeAndWindControls = hasTimeAndWindControls && ClientData.hasPermission();
		this.useTimeAndWindSync = useTimeAndWindSync;

		checkboxUseTimeAndWindSync = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.use_time_and_wind_sync"), PacketTrainDataGuiClient::sendUseTimeAndWindSyncC2S);

		buttonUseMTRFont = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useMTRFont = Config.setUseMTRFont(!useMTRFont);
			setButtonText(button, useMTRFont);
		});
		buttonShowAnnouncementMessages = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			showAnnouncementMessages = Config.setShowAnnouncementMessages(!showAnnouncementMessages);
			setButtonText(button, showAnnouncementMessages);
		});
		buttonUseTTSAnnouncements = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useTTSAnnouncements = Config.setUseTTSAnnouncements(!useTTSAnnouncements);
			setButtonText(button, useTTSAnnouncements);
		});
		buttonHideSpecialRailColors = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			hideSpecialRailColors = Config.setHideSpecialRailColors(!hideSpecialRailColors);
			setButtonText(button, hideSpecialRailColors);
		});
		buttonHideTranslucentParts = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			hideTranslucentParts = Config.setHideTranslucentParts(!hideTranslucentParts);
			setButtonText(button, hideTranslucentParts);
		});
		buttonOptimizeTrainRendering = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button ->
				setButtonText(button, Config.setOptimizeTrainRendering(!Config.optimizeTrainRendering())));
		buttonShowDebugInfo = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button ->
				setButtonText(button, Config.setShowDebugInfo(!Config.showDebugInfo())));
		buttonShiftToToggleSitting = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			shiftToToggleSitting = Config.setShiftToToggleSitting(!shiftToToggleSitting);
			setButtonText(button, shiftToToggleSitting);
		});
		buttonLanguageOptions = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			languageOptions = Config.setLanguageOptions(languageOptions + 1);
			button.setMessage(Text.translatable("options.mtr.language_options_" + languageOptions));
		});
		buttonUseDynamicFPS = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> {
			useDynamicFPS = Config.setUseDynamicFPS(!useDynamicFPS);
			setButtonText(button, useDynamicFPS);
		});
		sliderTrackTextureOffset = new WidgetShorterSlider(0, 0, Config.TRACK_OFFSET_COUNT - 1, Object::toString, null);
		sliderDynamicTextureResolution = new WidgetShorterSlider(0, 0, Config.DYNAMIC_RESOLUTION_COUNT - 1, Object::toString, null);
		sliderTrainRenderDistanceRatio = new WidgetShorterSlider(0, 0, Config.TRAIN_RENDER_DISTANCE_RATIO_COUNT - 1, num -> String.format("%d%%", (num + 1) * 100 / Config.TRAIN_RENDER_DISTANCE_RATIO_COUNT), null);
		buttonSupportPatreon = UtilitiesClient.newButton(BUTTON_HEIGHT, Text.literal(""), button -> Util.getPlatform().openUri("https://www.patreon.com/minecraft_transit_railway"));
	}

	@Override
	protected void init() {
		super.init();
		Config.refreshProperties();
		useMTRFont = Config.useMTRFont();
		showAnnouncementMessages = Config.showAnnouncementMessages();
		useTTSAnnouncements = Config.useTTSAnnouncements();
		hideSpecialRailColors = Config.hideSpecialRailColors();
		hideTranslucentParts = Config.hideTranslucentParts();
		shiftToToggleSitting = Config.shiftToToggleSitting();
		languageOptions = Config.languageOptions();
		useDynamicFPS = Config.useDynamicFPS();

		final int offsetY;
		if (hasTimeAndWindControls) {
			IDrawing.setPositionAndWidth(checkboxUseTimeAndWindSync, SQUARE_SIZE, SQUARE_SIZE, width);
			checkboxUseTimeAndWindSync.setChecked(useTimeAndWindSync);
			// Below the checkbox, a row of its own
			offsetY = SQUARE_SIZE;
		} else {
			offsetY = 0;
		}

		buttonX = getButtonX();
		int i = 1;
		IDrawing.setPositionAndWidth(buttonUseMTRFont, buttonX, SQUARE_SIZE + offsetY, BUTTON_WIDTH);
		if (!Keys.LIFTS_ONLY) {
			IDrawing.setPositionAndWidth(buttonShowAnnouncementMessages, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonUseTTSAnnouncements, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonHideSpecialRailColors, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonHideTranslucentParts, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonOptimizeTrainRendering, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonShowDebugInfo, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonShiftToToggleSitting, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonLanguageOptions, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(buttonUseDynamicFPS, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(sliderTrackTextureOffset, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH - TEXT_PADDING - font.width("100%"));
			IDrawing.setPositionAndWidth(sliderDynamicTextureResolution, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH - TEXT_PADDING - font.width("100%"));
			IDrawing.setPositionAndWidth(sliderTrainRenderDistanceRatio, buttonX, BUTTON_HEIGHT * (i++) + SQUARE_SIZE + offsetY, BUTTON_WIDTH - TEXT_PADDING - font.width("100%"));
		}
		IDrawing.setPositionAndWidth(buttonSupportPatreon, buttonX, BUTTON_HEIGHT * i + SQUARE_SIZE + offsetY, BUTTON_WIDTH);
		setButtonText(buttonUseMTRFont, useMTRFont);
		setButtonText(buttonShowAnnouncementMessages, showAnnouncementMessages);
		setButtonText(buttonUseTTSAnnouncements, useTTSAnnouncements);
		setButtonText(buttonHideSpecialRailColors, hideSpecialRailColors);
		setButtonText(buttonHideTranslucentParts, hideTranslucentParts);
		setButtonText(buttonOptimizeTrainRendering, Config.optimizeTrainRendering());
		setButtonText(buttonShowDebugInfo, Config.showDebugInfo());
		setButtonText(buttonShiftToToggleSitting, shiftToToggleSitting);
		buttonLanguageOptions.setMessage(Text.translatable("options.mtr.language_options_" + languageOptions));
		setButtonText(buttonUseDynamicFPS, useDynamicFPS);
		sliderTrackTextureOffset.setHeight(BUTTON_HEIGHT);
		sliderTrackTextureOffset.setValue(Config.trackTextureOffset());
		sliderDynamicTextureResolution.setHeight(BUTTON_HEIGHT);
		sliderDynamicTextureResolution.setValue(Config.dynamicTextureResolution());
		sliderTrainRenderDistanceRatio.setHeight(BUTTON_HEIGHT);
		sliderTrainRenderDistanceRatio.setValue(Config.trainRenderDistanceRatio());
		buttonSupportPatreon.setMessage(Text.translatable("gui.mtr.support"));

		if (hasTimeAndWindControls) {
			addDrawableChild(checkboxUseTimeAndWindSync);
		}
		addDrawableChild(buttonUseMTRFont);
		if (!Keys.LIFTS_ONLY) {
			addDrawableChild(buttonShowAnnouncementMessages);
			addDrawableChild(buttonUseTTSAnnouncements);
			addDrawableChild(buttonHideSpecialRailColors);
			addDrawableChild(buttonHideTranslucentParts);
			addDrawableChild(buttonOptimizeTrainRendering);
			addDrawableChild(buttonShowDebugInfo);
			addDrawableChild(buttonShiftToToggleSitting);
			addDrawableChild(buttonLanguageOptions);
			addDrawableChild(buttonUseDynamicFPS);
			addDrawableChild(sliderTrackTextureOffset);
			addDrawableChild(sliderDynamicTextureResolution);
			addDrawableChild(sliderTrainRenderDistanceRatio);
		}
		addDrawableChild(buttonSupportPatreon);
	}

	/**
	 * @return where the buttons start: just after the longest option text, not at the right edge of a wide screen (0.66.4)
	 */
	private int getButtonX() {
		final String[] keys = {"options.mtr.use_mtr_font", "options.mtr.show_announcement_messages", "options.mtr.use_tts_announcements", "options.mtr.hide_special_rail_colors", "options.mtr.hide_translucent_parts", "options.mtr.optimize_train_rendering", "options.mtr.show_debug_info", "options.mtr.language_options", "options.mtr.use_dynamic_fps", "options.mtr.track_texture_offset", "options.mtr.dynamic_texture_resolution", "options.mtr.vehicle_render_distance_ratio", "options.mtr.support_patreon"};
		int textWidth = font.width(Text.translatable("options.mtr.shift_to_toggle_sitting", minecraft == null ? "" : minecraft.options.keyShift.getTranslatedKeyMessage()));
		for (final String key : keys) {
			textWidth = Math.max(textWidth, font.width(Text.translatable(key)));
		}
		return Math.max(SQUARE_SIZE, Math.min(width - SQUARE_SIZE - BUTTON_WIDTH, SQUARE_SIZE + textWidth + SQUARE_SIZE));
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.mtr_options"), width / 2, TEXT_PADDING, ARGB_WHITE);

			final int yStart1 = SQUARE_SIZE + TEXT_PADDING / 2 + (hasTimeAndWindControls ? SQUARE_SIZE : 0);
			// Each option's row striped, and the one under the mouse lit, from its text to its button (0.66.4): with the
			// buttons at the right edge, a text was easily read with the button of the next row
			final int rowTop = SQUARE_SIZE + (hasTimeAndWindControls ? SQUARE_SIZE : 0);
			final int rows = Keys.LIFTS_ONLY ? 2 : 14;
			for (int row = 0; row < rows; row++) {
				final int top = rowTop + BUTTON_HEIGHT * row;
				final boolean hovered = mouseY >= top && mouseY < top + BUTTON_HEIGHT && mouseX >= SQUARE_SIZE - TEXT_PADDING && mouseX < buttonX + BUTTON_WIDTH + TEXT_PADDING;
				if (hovered || row % 2 == 0) {
					GuiCompat.fill(matrices, SQUARE_SIZE - TEXT_PADDING, top, buttonX + BUTTON_WIDTH + TEXT_PADDING, top + BUTTON_HEIGHT, hovered ? 0x40FFFFFF : 0x20FFFFFF);
				}
			}
			int i = 1;
			GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.use_mtr_font"), SQUARE_SIZE, yStart1, ARGB_WHITE);
			if (!Keys.LIFTS_ONLY) {
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.show_announcement_messages"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.use_tts_announcements"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.hide_special_rail_colors"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.hide_translucent_parts"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.optimize_train_rendering"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.show_debug_info"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.shift_to_toggle_sitting", minecraft == null ? "" : minecraft.options.keyShift.getTranslatedKeyMessage()), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.language_options"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.use_dynamic_fps"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.track_texture_offset"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.dynamic_texture_resolution"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
				GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.vehicle_render_distance_ratio"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);
			}
			GuiCompat.drawString(matrices, font, Text.translatable("options.mtr.support_patreon"), SQUARE_SIZE, BUTTON_HEIGHT * (i++) + yStart1, ARGB_WHITE);

			final int yStart2 = BUTTON_HEIGHT * (i + 1) + yStart1;
			String tierTitle = "";
			int y = 0;
			int x = 0;
			int maxWidth = 0;
			for (final Patreon patreon : Config.PATREON_LIST) {
				if (!patreon.tierTitle.equals(tierTitle)) {
					x += maxWidth + TEXT_PADDING;
					y = 0;
					final Component text = Text.literal(patreon.tierTitle);
					maxWidth = font.width(text);
					GuiCompat.drawString(matrices, font, text, SQUARE_SIZE - TEXT_PADDING + x, yStart2, patreon.tierColor);
				} else if (y + yStart2 + TEXT_HEIGHT + SQUARE_SIZE > height) {
					x += maxWidth + TEXT_PADDING;
					y = 0;
					maxWidth = 0;
				}

				tierTitle = patreon.tierTitle;
				final Component text = patreon.tierAmount < 1000 ? Text.translatable("options.mtr.anonymous") : Text.literal(patreon.name);
				maxWidth = Math.max(maxWidth, font.width(text));
				GuiCompat.drawString(matrices, font, text, SQUARE_SIZE - TEXT_PADDING + x, yStart2 + y + TEXT_HEIGHT + TEXT_PADDING, ARGB_LIGHT_GRAY);
				y += TEXT_HEIGHT + 2;
			}

			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClose() {
		super.onClose();
		Config.setTrackTextureOffset(sliderTrackTextureOffset.getIntValue());
		Config.setDynamicTextureResolution(sliderDynamicTextureResolution.getIntValue());
		Config.setTrainRenderDistanceRatio(sliderTrainRenderDistanceRatio.getIntValue());
		ClientData.DATA_CACHE.sync();
		ClientData.DATA_CACHE.refreshDynamicResources();
		ClientData.SIGNAL_BLOCKS.writeCache();
	}

	private static void setButtonText(Button button, boolean state) {
		button.setMessage(Text.translatable(state ? "options.mtr.on" : "options.mtr.off"));
	}
}
