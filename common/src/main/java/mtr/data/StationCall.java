package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import transitsim.motion.PlatformDwell;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** A real train's next passenger stop, retained through its approach and dwell. */
public final class StationCall {

	public enum Trigger {
		LEGACY, BEFORE_ARRIVAL, AFTER_ARRIVAL, BEFORE_DEPARTURE;

		public static Trigger read(String value) {
			for (final Trigger trigger : values()) {
				if (trigger.name().equals(value)) {
					return trigger;
				}
			}
			return LEGACY;
		}

		public boolean isDue(long now, int seconds, long approachMillis, PlatformDwell dwell) {
			final long interval = seconds * 1000L;
			switch (this) {
				case BEFORE_ARRIVAL:
					return dwell == null && approachMillis > now && approachMillis - now <= interval;
				case AFTER_ARRIVAL:
					return dwell != null && now - dwell.arrivedMillis >= interval;
				case BEFORE_DEPARTURE:
					return dwell != null && dwell.departureMillis > now && dwell.departureMillis - now <= interval;
				default:
					return false;
			}
		}
	}

	public final long platformId;
	private final TrainServer train;
	private final Set<BlockPos> triggered = new HashSet<>();
	long routeId;
	long approachMillis;
	PlatformDwell dwell;

	StationCall(long platformId, TrainServer train) {
		this.platformId = platformId;
		this.train = train;
	}

	public long getRouteId() {
		return routeId;
	}

	public boolean trigger(BlockPos pos, Trigger trigger, int seconds, long now) {
		return trigger.isDue(now, seconds, approachMillis, dwell) && triggered.add(pos.immutable());
	}

	public void forEachRider(Consumer<Player> consumer) {
		train.forEachRider(consumer);
	}

	public boolean isRider(Player player) {
		return train.isPlayerRiding(player);
	}
}
