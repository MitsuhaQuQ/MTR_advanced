package mtr.render;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Items;
import mtr.client.ClientData;
import mtr.compat.GuiCompat;
import mtr.data.TrainClient;
import mtr.mappings.Text;
import mtr.packet.TrainDebugPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The debug display (0.65.31): holding the brush and looking at a train, its planned and expected times, how late it is,
 * how long it has stood and until the doors close, why it is held and how fast it may go, as the server has them
 * ({@link TrainDebugPackets}), at the top right of the screen.
 */
public final class RenderTrainDebug {

	private static final double MAX_DISTANCE = 96;
	private static final double MAX_AIM_DISTANCE = 3;
	private static final long STALE_MILLIS = 3000;
	private static final int FIND_EVERY_FRAMES = 4;
	private static final int PADDING = 4;
	private static final int LINE_HEIGHT = 10;

	private static int frames;
	private static long aimedTrain;

	private RenderTrainDebug() {
	}

	public static void render(PoseStack matrices) {
		final Minecraft client = Minecraft.getInstance();
		final LocalPlayer player = client.player;
		final Window window = client.getWindow();
		if (player == null || window == null || client.options.hideGui || !player.isHolding(Items.BRUSH.get())) {
			aimedTrain = 0;
			return;
		}
		if (frames++ % FIND_EVERY_FRAMES == 0) {
			aimedTrain = findAimedTrain(player.getEyePosition(1), player.getViewVector(1));
		}
		if (aimedTrain == 0) {
			return;
		}
		TrainDebugPackets.sendRequestC2S(aimedTrain);
		if (TrainDebugPackets.clientTrainId != aimedTrain || System.currentTimeMillis() - TrainDebugPackets.clientReceivedMillis > STALE_MILLIS) {
			return;
		}

		final List<String> texts = new ArrayList<>();
		texts.add(Text.translatable("gui.mtr.train_debug_title").getString());
		final List<String[]> lines = TrainDebugPackets.clientLines;
		if (lines.isEmpty()) {
			texts.add(Text.translatable("gui.mtr.train_debug_value_gone").getString());
		}
		lines.forEach(line -> texts.add(Text.translatable("gui.mtr.train_debug_" + line[0]).getString() + ": " + value(line[1])));
		int width = 0;
		for (final String text : texts) {
			width = Math.max(width, client.font.width(text));
		}
		final int right = window.getGuiScaledWidth() - PADDING;
		final int left = right - width - PADDING * 2;
		final int top = PADDING;
		GuiCompat.fill(matrices, left, top, right, top + texts.size() * LINE_HEIGHT + PADDING * 2, 0xA0000000);
		for (int i = 0; i < texts.size(); i++) {
			GuiCompat.drawShadow(matrices, client.font, texts.get(i), left + PADDING, top + PADDING + i * LINE_HEIGHT, i == 0 ? 0xFFFFDD55 : 0xFFFFFFFF);
		}
	}

	/**
	 * A value as sent, each word starting with "#" put in the player's language.
	 */
	static String value(String value) {
		if (value.indexOf('#') < 0) {
			return value;
		}
		final StringBuilder builder = new StringBuilder();
		for (final String word : value.split(" ")) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(word.startsWith("#") ? Text.translatable("gui.mtr.train_debug_value_" + word.substring(1)).getString() : word);
		}
		return builder.toString();
	}

	/**
	 * @return the train whose cars are nearest the line the player looks along, or 0
	 */
	private static long findAimedTrain(Vec3 eye, Vec3 look) {
		long best = 0;
		double bestAlong = Double.MAX_VALUE;
		for (final TrainClient train : ClientData.TRAINS) {
			for (int car = 0; car < train.trainCars; car++) {
				final Vec3 position = train.getTrackPosition(car, 0);
				if (position == null) {
					continue;
				}
				final Vec3 offset = position.add(0, 1.5, 0).subtract(eye);
				final double along = offset.dot(look);
				if (along < 0 || along > MAX_DISTANCE) {
					continue;
				}
				if (offset.subtract(look.scale(along)).length() < MAX_AIM_DISTANCE && along < bestAlong) {
					bestAlong = along;
					best = train.id;
				}
			}
		}
		return best;
	}
}
