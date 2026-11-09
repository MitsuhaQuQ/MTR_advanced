package mtr.block;

import mtr.BlockEntityTypes;
import mtr.data.RailwayData;
import mtr.data.ScheduleEntry;
import mtr.data.StationCall;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.TickableMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class BlockTrainScheduleSensor extends BlockTrainPoweredSensorBase {

	public BlockTrainScheduleSensor() {
		super();
	}

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityTrainScheduleSensor(pos, state);
	}

	@Override
	public <T extends BlockEntityMapper> void tick(Level world, BlockPos pos, T blockEntity) {
		TileEntityTrainScheduleSensor.tick(world, pos, blockEntity);
	}

	@Override
	public BlockEntityType<? extends BlockEntityMapper> getType() {
		return BlockEntityTypes.TRAIN_SCHEDULE_SENSOR_TILE_ENTITY.get();
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	public static class TileEntityTrainScheduleSensor extends TileEntityTrainSensorBase implements TickableMapper {

		private int seconds = 10;
		private StationCall.Trigger trigger = StationCall.Trigger.LEGACY;
		private long lastTrainId;
		private long lastArrivalMillis;
		private static final long SAME_ARRIVAL_MILLIS = 30000;
		private static final String KEY_SECONDS = "seconds";
		private static final String KEY_TRIGGER = "station_trigger";

		public TileEntityTrainScheduleSensor(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.TRAIN_SCHEDULE_SENSOR_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public void tick() {
			if (level != null) {
				tick(level, worldPosition, this);
			}
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			seconds = compoundTag.getInt(KEY_SECONDS);
			trigger = StationCall.Trigger.read(compoundTag.getString(KEY_TRIGGER));
			super.readCompoundTag(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putInt(KEY_SECONDS, seconds);
			compoundTag.putString(KEY_TRIGGER, trigger.name());
			super.writeCompoundTag(compoundTag);
		}

		@Override
		public void setData(Set<Long> filterRouteIds, boolean stoppedOnly, boolean movingOnly, int number, String... strings) {
			seconds = number;
			if (strings.length >= 2) {
				trigger = StationCall.Trigger.read(strings[1]);
			}
			setData(filterRouteIds, stoppedOnly, movingOnly);
		}

		/**
		 * The estimated arrival moves as a train runs, so one train and one arrival must only fire once.
		 */
		private boolean isNewTrain(ScheduleEntry scheduleEntry) {
			if (scheduleEntry.trainId == lastTrainId && Math.abs(scheduleEntry.arrivalMillis - lastArrivalMillis) < SAME_ARRIVAL_MILLIS) {
				return false;
			}
			lastTrainId = scheduleEntry.trainId;
			lastArrivalMillis = scheduleEntry.arrivalMillis;
			return true;
		}

		public int getSeconds() {
			return seconds;
		}

		public StationCall.Trigger getTrigger() {
			return trigger;
		}

		public static <T extends BlockEntityMapper> void tick(Level world, BlockPos pos, T blockEntity) {
			if (world != null && !world.isClientSide) {
				final BlockState state = world.getBlockState(pos);
				final Block block = state.getBlock();
				final boolean isActive = IBlock.getStatePropertySafe(state, POWERED) > 1 && world.getBlockTicks().hasScheduledTick(pos, block);

				if (isActive || !(block instanceof BlockTrainScheduleSensor) || !(blockEntity instanceof BlockTrainScheduleSensor.TileEntityTrainScheduleSensor)) {
					return;
				}

				final RailwayData railwayData = RailwayData.getInstance(world);
				if (railwayData == null) {
					return;
				}

				final TileEntityTrainScheduleSensor sensor = (TileEntityTrainScheduleSensor) blockEntity;
				if (sensor.trigger != StationCall.Trigger.LEGACY) {
					final long now = System.currentTimeMillis();
					if (railwayData.getStationCalls(pos).stream().anyMatch(call -> sensor.matchesFilter(call.getRouteId(), -1) && call.trigger(pos, sensor.trigger, sensor.seconds, now))) {
						((BlockTrainScheduleSensor) block).power(world, state, pos);
					}
					return;
				}

				final long platformId = RailwayData.getClosePlatformId(railwayData.platforms, railwayData.dataCache, pos, 4, 4, 0);
				if (platformId == 0) {
					return;
				}

				final List<ScheduleEntry> schedules = railwayData.getSchedulesAtPlatform(platformId);
				if (schedules == null) {
					return;
				}

				final List<ScheduleEntry> scheduleList = new ArrayList<>();
				schedules.forEach(scheduleEntry -> {
					if (sensor.matchesFilter(scheduleEntry.routeId, -1)) {
						scheduleList.add(scheduleEntry);
					}
				});
				if (!scheduleList.isEmpty()) {
					Collections.sort(scheduleList);
					final ScheduleEntry scheduleEntry = scheduleList.get(0);
					final long remainingMillis = scheduleEntry.arrivalMillis - System.currentTimeMillis();
					if (remainingMillis >= 0 && remainingMillis / 1000 <= sensor.seconds && sensor.isNewTrain(scheduleEntry)) {
						((BlockTrainScheduleSensor) block).power(world, state, pos);
					}
				}
			}
		}
	}
}
