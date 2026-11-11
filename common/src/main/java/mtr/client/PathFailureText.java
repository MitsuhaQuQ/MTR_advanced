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
				return failure.node1 == 0 ? Text.translatable("gui.mtr.path_failure_disconnected").getString() : Text.translatable("gui.mtr.path_failure_disconnected_between", platformName(failure.node1), platformName(failure.node2)).getString();
			case NO_ROUTE:
				return failure.node1 == 0 ? Text.translatable("gui.mtr.path_failure_no_route").getString() : Text.translatable("gui.mtr.path_failure_no_route_between", platformName(failure.node1), platformName(failure.node2)).getString();
			case WAYPOINT_UNREACHABLE:
				return Text.translatable("gui.mtr.path_failure_waypoint_unreachable", platformName(failure.node1), platformName(failure.node2), format(pos)).getString();
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
	 * @param reason why a siding has no way out or back, as {@link mtr.data.DepotLegs#outReasons}
	 * @return the reason in words, or empty if not known
	 */
	public static String depotWay(long[] reason) {
		if (reason == null || reason.length == 0) {
			return "";
		}
		switch ((int) reason[0]) {
			case mtr.data.DepotLegs.REASON_NO_TRACK:
				return Text.translatable("gui.mtr.depot_way_no_track").getString();
			case mtr.data.DepotLegs.REASON_AGAINST:
				final StringBuilder platforms = new StringBuilder();
				for (int i = 1; i < reason.length; i++) {
					platforms.append(i > 1 ? ", " : "").append(platformName(reason[i]));
				}
				return Text.translatable("gui.mtr.depot_way_against", platforms.toString()).getString();
			case mtr.data.DepotLegs.REASON_ENTRANCE_UNSET:
				return Text.translatable("gui.mtr.depot_way_entrance_unset").getString();
			case mtr.data.DepotLegs.REASON_RULE:
				return Text.translatable("gui.mtr.depot_way_rule").getString();
			case mtr.data.DepotLegs.REASON_WRONG_WAY:
				return Text.translatable("gui.mtr.depot_way_wrong_way").getString();
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
