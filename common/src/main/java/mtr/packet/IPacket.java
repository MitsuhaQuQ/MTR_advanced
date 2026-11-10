package mtr.packet;

import mtr.Keys;
import mtr.MTR;
import net.minecraft.resources.ResourceLocation;

public interface IPacket {

	/**
	 * What a client and its server must have the same of: this fork's version (MTM_Logic, {@code mtm_version} in
	 * gradle.properties), raised whenever packets or saved data change. MTR's own version stays 3.2.2 for add-ons, so it
	 * tells nothing. It differs from what builds before it sent (MTR's version and a protocol number), so they don't match
	 * either.
	 */
	static String getNetworkVersion() {
		return "MTM_Logic " + Keys.MTM_VERSION;
	}

	ResourceLocation PACKET_VERSION_CHECK = new ResourceLocation(MTR.MOD_ID, "packet_version_check");
	ResourceLocation PACKET_LINE_WORDS = new ResourceLocation(MTR.MOD_ID, "packet_line_words");
	ResourceLocation PACKET_TIMETABLE_CONFIG = new ResourceLocation(MTR.MOD_ID, "packet_timetable_config");
	ResourceLocation PACKET_EDIT_PERMISSION = new ResourceLocation(MTR.MOD_ID, "packet_edit_permission");

	ResourceLocation PACKET_OPEN_DASHBOARD_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_dashboard_screen");
	ResourceLocation PACKET_OPEN_PIDS_CONFIG_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_pids_config_screen");
	ResourceLocation PACKET_OPEN_ARRIVAL_PROJECTOR_CONFIG_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_arrival_projector_config_screen");
	ResourceLocation PACKET_OPEN_RAILWAY_SIGN_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_railway_sign_screen");
	ResourceLocation PACKET_OPEN_STATION_AREA_TOOL = new ResourceLocation(MTR.MOD_ID, "packet_open_station_area_tool");
	ResourceLocation PACKET_OPEN_TICKET_MACHINE_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_ticket_machine_screen");
	ResourceLocation PACKET_OPEN_TRAIN_SENSOR_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_train_sensor_screen");
	ResourceLocation PACKET_OPEN_NODE_ANGLE_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_node_angle_screen");
	ResourceLocation PACKET_SET_KEY_NODE = new ResourceLocation(MTR.MOD_ID, "packet_set_key_node");
	ResourceLocation PACKET_PATH_REGIONS = new ResourceLocation(MTR.MOD_ID, "packet_path_regions");
	ResourceLocation PACKET_UPDATE_PATH_BOUNDARY = new ResourceLocation(MTR.MOD_ID, "packet_update_path_boundary");
	ResourceLocation PACKET_REQUEST_MAP_RAILS = new ResourceLocation(MTR.MOD_ID, "packet_request_map_rails");
	ResourceLocation PACKET_MAP_DIGEST = new ResourceLocation(MTR.MOD_ID, "packet_map_digest");
	ResourceLocation PACKET_MAP_WANTED = new ResourceLocation(MTR.MOD_ID, "packet_map_wanted");
	ResourceLocation PACKET_MAP_DATA = new ResourceLocation(MTR.MOD_ID, "packet_map_data");
	ResourceLocation PACKET_STALE_DEPOTS = new ResourceLocation(MTR.MOD_ID, "packet_stale_depots");
	ResourceLocation PACKET_REQUEST_MAP_ROUTE = new ResourceLocation(MTR.MOD_ID, "packet_request_map_route");
	ResourceLocation PACKET_OPEN_LIFT_TRACK_FLOOR_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_lift_track_floor_screen");
	ResourceLocation PACKET_OPEN_LIFT_CUSTOMIZATION_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_lift_customization_screen");
	ResourceLocation PACKET_OPEN_RESOURCE_PACK_CREATOR_SCREEN = new ResourceLocation(MTR.MOD_ID, "packet_open_resource_pack_creator_screen");

	ResourceLocation PACKET_ANNOUNCE = new ResourceLocation(MTR.MOD_ID, "packet_announce");
	ResourceLocation PACKET_OPEN_AUDIO_SPEAKER = new ResourceLocation(MTR.MOD_ID, "packet_open_audio_speaker");
	ResourceLocation PACKET_UPDATE_AUDIO_SPEAKER = new ResourceLocation(MTR.MOD_ID, "packet_update_audio_speaker");
	ResourceLocation PACKET_AUDIO_SPEAKER_PLAYBACK = new ResourceLocation(MTR.MOD_ID, "packet_audio_speaker_playback");
	ResourceLocation PACKET_USE_TIME_AND_WIND_SYNC = new ResourceLocation(MTR.MOD_ID, "packet_use_time_and_wind_sync");

	ResourceLocation PACKET_CREATE_RAIL = new ResourceLocation(MTR.MOD_ID, "packet_create_rail");
	ResourceLocation PACKET_CREATE_SIGNAL = new ResourceLocation(MTR.MOD_ID, "packet_create_signal");
	ResourceLocation PACKET_REMOVE_NODE = new ResourceLocation(MTR.MOD_ID, "packet_remove_node");
	ResourceLocation PACKET_REMOVE_LIFT_FLOOR_TRACK = new ResourceLocation(MTR.MOD_ID, "packet_remove_lift_floor_track");
	ResourceLocation PACKET_REMOVE_RAIL = new ResourceLocation(MTR.MOD_ID, "packet_remove_rail");
	ResourceLocation PACKET_REMOVE_SIGNALS = new ResourceLocation(MTR.MOD_ID, "packet_remove_signals");
	ResourceLocation PACKET_REMOVE_RAIL_ACTION = new ResourceLocation(MTR.MOD_ID, "packet_remove_rail_action");

	ResourceLocation PACKET_GENERATE_PATH = new ResourceLocation(MTR.MOD_ID, "packet_generate_path");
	ResourceLocation PACKET_PLACE_TRAINS = new ResourceLocation(MTR.MOD_ID, "packet_place_trains");
	ResourceLocation PACKET_REPLAN_DUTIES = new ResourceLocation(MTR.MOD_ID, "packet_replan_duties");
	ResourceLocation PACKET_AUTO_DUTIES = new ResourceLocation(MTR.MOD_ID, "packet_auto_duties");
	ResourceLocation PACKET_CLEAR_TRAINS = new ResourceLocation(MTR.MOD_ID, "packet_clear_trains");
	ResourceLocation PACKET_SIGN_TYPES = new ResourceLocation(MTR.MOD_ID, "packet_sign_types");
	ResourceLocation PACKET_DRIVE_TRAIN = new ResourceLocation(MTR.MOD_ID, "packet_drive_train");
	ResourceLocation PACKET_PRESS_LIFT_BUTTON = new ResourceLocation(MTR.MOD_ID, "packet_press_lift_button");
	ResourceLocation PACKET_ADD_BALANCE = new ResourceLocation(MTR.MOD_ID, "packet_add_balance");
	ResourceLocation PACKET_PIDS_UPDATE = new ResourceLocation(MTR.MOD_ID, "packet_pids_update");
	ResourceLocation PACKET_ARRIVAL_PROJECTOR_UPDATE = new ResourceLocation(MTR.MOD_ID, "packet_arrival_projector_update");
	ResourceLocation PACKET_CHUNK_S2C = new ResourceLocation(MTR.MOD_ID, "packet_chunk_s2c");

	ResourceLocation PACKET_UPDATE_STATION = new ResourceLocation(MTR.MOD_ID, "packet_update_station");
	ResourceLocation PACKET_UPDATE_PLATFORM = new ResourceLocation(MTR.MOD_ID, "packet_update_platform");
	ResourceLocation PACKET_UPDATE_SIDING = new ResourceLocation(MTR.MOD_ID, "packet_update_siding");
	ResourceLocation PACKET_UPDATE_ROUTE = new ResourceLocation(MTR.MOD_ID, "packet_update_route");
	ResourceLocation PACKET_UPDATE_DEPOT = new ResourceLocation(MTR.MOD_ID, "packet_update_depot");
	ResourceLocation PACKET_UPDATE_LIFT = new ResourceLocation(MTR.MOD_ID, "packet_update_lift");

	ResourceLocation PACKET_DELETE_STATION = new ResourceLocation(MTR.MOD_ID, "packet_delete_station");
	ResourceLocation PACKET_DELETE_PLATFORM = new ResourceLocation(MTR.MOD_ID, "packet_delete_platform");
	ResourceLocation PACKET_DELETE_SIDING = new ResourceLocation(MTR.MOD_ID, "packet_delete_siding");
	ResourceLocation PACKET_DELETE_ROUTE = new ResourceLocation(MTR.MOD_ID, "packet_delete_route");
	ResourceLocation PACKET_DELETE_DEPOT = new ResourceLocation(MTR.MOD_ID, "packet_delete_depot");

	ResourceLocation PACKET_WRITE_RAILS = new ResourceLocation(MTR.MOD_ID, "write_rails");
	ResourceLocation PACKET_UPDATE_TRAINS = new ResourceLocation(MTR.MOD_ID, "update_trains");
	ResourceLocation PACKET_DELETE_TRAINS = new ResourceLocation(MTR.MOD_ID, "delete_trains");
	ResourceLocation PACKET_UPDATE_TRAIN_STATES = new ResourceLocation(MTR.MOD_ID, "update_train_states");
	ResourceLocation PACKET_EDIT = new ResourceLocation(MTR.MOD_ID, "packet_edit");
	ResourceLocation PACKET_EDIT_BATCH = new ResourceLocation(MTR.MOD_ID, "packet_edit_batch");
	ResourceLocation PACKET_EDITING = new ResourceLocation(MTR.MOD_ID, "packet_editing");
	ResourceLocation PACKET_EDIT_REVISIONS = new ResourceLocation(MTR.MOD_ID, "packet_edit_revisions");
	ResourceLocation PACKET_EDIT_DATA = new ResourceLocation(MTR.MOD_ID, "packet_edit_data");
	ResourceLocation PACKET_EDITORS = new ResourceLocation(MTR.MOD_ID, "packet_editors");
	ResourceLocation PACKET_EDIT_RESULT = new ResourceLocation(MTR.MOD_ID, "packet_edit_result");
	ResourceLocation PACKET_ROUTE_PREVIEW = new ResourceLocation(MTR.MOD_ID, "packet_route_preview");
	ResourceLocation PACKET_DEPOT_WAY_PREVIEW = new ResourceLocation(MTR.MOD_ID, "packet_depot_way_preview");
	ResourceLocation PACKET_HELD_TRAINS = new ResourceLocation(MTR.MOD_ID, "packet_held_trains");
	ResourceLocation PACKET_RECALL_TRAIN = new ResourceLocation(MTR.MOD_ID, "packet_recall_train");
	ResourceLocation PACKET_DELAY_ALARMS = new ResourceLocation(MTR.MOD_ID, "packet_delay_alarms");
	ResourceLocation PACKET_CONTINGENCIES = new ResourceLocation(MTR.MOD_ID, "packet_contingencies");
	ResourceLocation PACKET_NETWORK_SETTINGS = new ResourceLocation(MTR.MOD_ID, "packet_network_settings");
	ResourceLocation PACKET_TRACK_OWNERS = new ResourceLocation(MTR.MOD_ID, "packet_track_owners");
	/**
	 * The dispatcher's console (D6): asked for and sent back, and a command for a train.
	 */
	ResourceLocation PACKET_CONSOLE = new ResourceLocation(MTR.MOD_ID, "packet_console");
	ResourceLocation PACKET_DISPATCH = new ResourceLocation(MTR.MOD_ID, "packet_dispatch");
	ResourceLocation PACKET_UPDATE_LIFTS = new ResourceLocation(MTR.MOD_ID, "update_lifts");
	ResourceLocation PACKET_DELETE_LIFTS = new ResourceLocation(MTR.MOD_ID, "delete_lifts");
	ResourceLocation PACKET_UPDATE_TRAIN_PASSENGERS = new ResourceLocation(MTR.MOD_ID, "update_train_passengers");
	ResourceLocation PACKET_UPDATE_TRAIN_PASSENGER_POSITION = new ResourceLocation(MTR.MOD_ID, "update_train_passenger_position");
	ResourceLocation PACKET_UPDATE_LIFT_PASSENGERS = new ResourceLocation(MTR.MOD_ID, "update_lift_passengers");
	ResourceLocation PACKET_UPDATE_LIFT_PASSENGER_POSITION = new ResourceLocation(MTR.MOD_ID, "update_lift_passenger_position");
	ResourceLocation PACKET_UPDATE_ENTITY_SEAT_POSITION = new ResourceLocation(MTR.MOD_ID, "update_entity_seat_position");
	ResourceLocation PACKET_UPDATE_RAIL_ACTIONS = new ResourceLocation(MTR.MOD_ID, "update_rail_actions");
	ResourceLocation PACKET_UPDATE_SCHEDULE = new ResourceLocation(MTR.MOD_ID, "update_schedule");
	ResourceLocation PACKET_UPDATE_TRAIN_SENSOR = new ResourceLocation(MTR.MOD_ID, "packet_update_train_announcer");
	ResourceLocation PACKET_TIMETABLE_TIMES = new ResourceLocation(MTR.MOD_ID, "packet_timetable_times");
	ResourceLocation PACKET_STATION_LAYOUT = new ResourceLocation(MTR.MOD_ID, "packet_station_layout");
	ResourceLocation PACKET_ROUTE_LAYOUT = new ResourceLocation(MTR.MOD_ID, "packet_route_layout");
	ResourceLocation PACKET_SIMULATE = new ResourceLocation(MTR.MOD_ID, "packet_simulate");
	ResourceLocation PACKET_SIMULATE_RESULT = new ResourceLocation(MTR.MOD_ID, "packet_simulate_result");
	ResourceLocation PACKET_SERVER_TIME = new ResourceLocation(MTR.MOD_ID, "packet_server_time");
	ResourceLocation PACKET_TIMETABLE_CONFLICTS = new ResourceLocation(MTR.MOD_ID, "packet_timetable_conflicts");
	ResourceLocation PACKET_UPDATE_NODE_ANGLE = new ResourceLocation(MTR.MOD_ID, "packet_update_node_angle");
	ResourceLocation PACKET_UPDATE_LIFT_TRACK_FLOOR = new ResourceLocation(MTR.MOD_ID, "packet_update_lift_track_floor");

	ResourceLocation PACKET_AUDIO_LIST = new ResourceLocation(MTR.MOD_ID, "packet_audio_list");
	ResourceLocation PACKET_IMAGE_ACTION = new ResourceLocation(MTR.MOD_ID, "packet_image_action");
	ResourceLocation PACKET_IMAGE_DATA = new ResourceLocation(MTR.MOD_ID, "packet_image_data");
	ResourceLocation PACKET_AUDIO_RESULT = new ResourceLocation(MTR.MOD_ID, "packet_audio_result");
	ResourceLocation PACKET_AUDIO_DATA = new ResourceLocation(MTR.MOD_ID, "packet_audio_data");
	ResourceLocation PACKET_AUDIO_UPLOAD = new ResourceLocation(MTR.MOD_ID, "packet_audio_upload");
	ResourceLocation PACKET_AUDIO_DELETE = new ResourceLocation(MTR.MOD_ID, "packet_audio_delete");
	ResourceLocation PACKET_AUDIO_WANTED = new ResourceLocation(MTR.MOD_ID, "packet_audio_wanted");
	ResourceLocation PACKET_AUDIO_SOUND_ID = new ResourceLocation(MTR.MOD_ID, "packet_audio_sound_id");

	int MAX_PACKET_BYTES = 1048576;
}
