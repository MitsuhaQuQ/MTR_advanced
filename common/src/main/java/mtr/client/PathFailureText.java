package mtr.client;

import mtr.data.IGui;
import mtr.data.Route;
import mtr.mappings.Text;
import mtr.path.PathFailure;
import net.minecraft.core.BlockPos;

/**
 * Why a path couldn't be found, in words, for the depot screen and the advanced editor's route view.
 */
public final class PathFailureText {

	private PathFailureText() {
	}

	/**
	 * @return the message, or empty for {@link PathFailure#NONE}
	 */
	public static String get(PathFailure failure) {
		final BlockPos pos = BlockPos.of(failure.pos);
		switch (failure.type) {
			case OLD_SEARCH:
				return Text.translatable("gui.mtr.path_failure_old_search").getString();
			case MISSING_WAYPOINT:
				return Text.translatable("gui.mtr.path_failure_missing_waypoint", format(pos)).getString();
			case DISCONNECTED:
				return Text.translatable("gui.mtr.path_failure_disconnected").getString();
			case NO_ROUTE:
				return Text.translatable("gui.mtr.path_failure_no_route").getString();
			case TURN_BACK:
				return Text.translatable("gui.mtr.path_failure_turn_back", format(pos)).getString();
			case TURN_BACK_UNUSED:
				return Text.translatable("gui.mtr.path_failure_turn_back_unused", format(pos)).getString();
			case DUTY:
				return Text.translatable("gui.mtr.path_failure_duty").getString();
			case BOUNDARY:
				return Text.translatable("gui.mtr.path_failure_boundary", format(pos)).getString();
			case CONNECTING_LINE:
				final Route connectingLine = ClientData.DATA_CACHE.routeIdMap.get(failure.pos);
				// The rail in the way, by the middle of its two ends
				final BlockPos node1 = BlockPos.of(failure.node1);
				final BlockPos node2 = BlockPos.of(failure.node2);
				final BlockPos middle = new BlockPos((node1.getX() + node2.getX()) / 2, (node1.getY() + node2.getY()) / 2, (node1.getZ() + node2.getZ()) / 2);
				return Text.translatable("gui.mtr.path_failure_connecting_line", connectingLine == null ? String.valueOf(failure.pos) : IGui.formatStationName(connectingLine.name), format(middle)).getString();
			case NOT_KEY_NODE:
				return Text.translatable("gui.mtr.path_failure_not_key_node", format(pos)).getString();
			case OTHER_LINE:
				final BlockPos end1 = BlockPos.of(failure.node1);
				final BlockPos end2 = BlockPos.of(failure.node2);
				return Text.translatable("gui.mtr.path_failure_other_line", format(new BlockPos((end1.getX() + end2.getX()) / 2, (end1.getY() + end2.getY()) / 2, (end1.getZ() + end2.getZ()) / 2))).getString();
			case CYCLE_BACK:
				return Text.translatable("gui.mtr.path_failure_cycle_back", platformName(failure.pos), platformName(failure.node1)).getString();
			default:
				return "";
		}
	}

	/**
	 * @return the platform as its station's name and its own, or its ID if it's gone
	 */
	private static String platformName(long platformId) {
		final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId);
		if (platform == null) {
			return String.valueOf(platformId);
		}
		final mtr.data.Station station = ClientData.DATA_CACHE.platformIdToStation.get(platformId);
		return (station == null ? "" : IGui.formatStationName(station.name) + " ") + platform.name;
	}

	private static String format(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}
