package mtr.screen;

import mtr.block.BlockTrainScheduleSensor;
import mtr.data.StationCall;
import mtr.mappings.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class TrainScheduleSensorScreen extends TrainSensorScreenBase {

	private final int seconds;
	private StationCall.Trigger trigger = StationCall.Trigger.LEGACY;
	private int trainKind = BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.KIND_ALL;

	private static final int MAX_SECONDS_LENGTH = 5;
	private static final int DEFAULT_SECONDS = 10;

	public TrainScheduleSensorScreen(BlockPos pos) {
		super(pos, false, true, new Tuple<>(new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, "", MAX_SECONDS_LENGTH), Text.translatable("gui.mtr.station_trigger_seconds")));

		final Level world = Minecraft.getInstance().level;
		if (world == null) {
			seconds = 0;
		} else {
			final BlockEntity entity = world.getBlockEntity(pos);
			seconds = entity instanceof BlockTrainScheduleSensor.TileEntityTrainScheduleSensor ? ((BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) entity).getSeconds() : DEFAULT_SECONDS;
			if (entity instanceof BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) {
				trigger = ((BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) entity).getTrigger();
				trainKind = ((BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) entity).getTrainKind();
			}
		}
	}

	@Override
	protected void init() {
		super.init();
		textFields[0].setValue(String.valueOf(seconds));
		addTriggerButton(() -> trigger, value -> trigger = value, (width - SQUARE_SIZE * 3) / 2);
		// Which trains: all, only those stopping, only those running through (2026-11-12)
		buttonTrainKind = mtr.mappings.UtilitiesClient.newButton(kindText(), pressed -> {
			trainKind = (trainKind + 1) % 3;
			pressed.setMessage(kindText());
		});
		mtr.client.IDrawing.setPositionAndWidth(buttonTrainKind, SQUARE_SIZE * 2 + (width - SQUARE_SIZE * 3) / 2, optionsY, (width - SQUARE_SIZE * 3) / 2);
		addDrawableChild(buttonTrainKind);
	}

	@Override
	protected int getNumber() {
		int secondsParsed = 10;
		try {
			secondsParsed = Integer.parseInt(textFields[0].getValue());
		} catch (Exception ignored) {
		}
		return trigger == StationCall.Trigger.LEGACY ? secondsParsed : Math.max(0, secondsParsed);
	}

	@Override
	protected String[] getTimingSettings() {
		return new String[]{trigger.name(), String.valueOf(trainKind)};
	}

	private net.minecraft.client.gui.components.Button buttonTrainKind;

	private net.minecraft.network.chat.Component kindText() {
		return Text.translatable(trainKind == BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.KIND_STOPPING ? "gui.mtr.station_train_kind_stopping" : trainKind == BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.KIND_PASSING ? "gui.mtr.station_train_kind_passing" : "gui.mtr.station_train_kind_all");
	}
}
