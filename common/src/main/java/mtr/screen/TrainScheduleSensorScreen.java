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
			}
		}
	}

	@Override
	protected void init() {
		super.init();
		textFields[0].setValue(String.valueOf(seconds));
		addTriggerButton(() -> trigger, value -> trigger = value, (width - SQUARE_SIZE * 3) / 2);
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
		return new String[]{trigger.name()};
	}
}
