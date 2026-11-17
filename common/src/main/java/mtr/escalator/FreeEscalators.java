package mtr.escalator;

import mtr.Blocks;
import mtr.block.BlockFreeEscalator;
import mtr.block.BlockFreeEscalator.TileEntityFreeEscalator;
import mtr.mappings.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Free escalators in the world (0.100.0): the points a player marks with the free escalator item, building an
 * escalator from them in one go, taking a whole escalator away when any of its blocks goes, the brush's up / down / stop
 * and the settings (speed, width), and carrying players and mobs standing on a running one.
 * <p>
 * Carrying: the first block ticks on both sides; on the server it moves mobs, on a client only that client's own
 * player (players' movement is their client's), each tick by the steps' speed along the run and up or down onto the
 * surface where it will then be.
 */
public final class FreeEscalators {

	/** Speeds the settings offer, metres a second along the steepest part */
	public static final double[] SPEEDS = {0.5, 0.65, 0.75};
	public static final double[] WIDTHS = {1, 2};

	private static final Map<UUID, List<double[]>> MARKS = new HashMap<>();
	private static boolean removing;
	/** On the server, the loaded blocks of each escalator by its id, per dimension */
	private static final Map<Level, Map<Long, Set<Long>>> LOADED = new WeakHashMap<>();
	/** Blocks loaded since the last tick, to be checked then (not while their chunk is being loaded) */
	private static final List<Object[]> TO_CHECK = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("MTR");
	private static boolean tickFailed;

	private FreeEscalators() {
	}

	// ---- Marking ----

	/** The free escalator item used on a block: its top middle is the next point */
	public static void mark(Player player, BlockPos clicked) {
		final List<double[]> marks = MARKS.computeIfAbsent(player.getUUID(), uuid -> new ArrayList<>());
		final double[] point = {clicked.getX() + 0.5, clicked.getY() + 1 + FreeEscalatorPath.DECK, clicked.getZ() + 0.5};
		marks.add(point);
		final MutableComponent message = Text.translatable("gui.mtr.free_escalator_point", marks.size(), clicked.getX(), clicked.getY() + 1, clicked.getZ());
		if (marks.size() > 1) {
			final double[] previous = marks.get(marks.size() - 2);
			final double horizontal = Math.hypot(point[0] - previous[0], point[2] - previous[2]);
			final double degrees = horizontal == 0 ? 90 : Math.toDegrees(Math.atan(Math.abs(point[1] - previous[1]) / horizontal));
			message.append(Text.translatable("gui.mtr.free_escalator_point_slope", Math.round(degrees)));
		}
		message.append(" ").append(button("gui.mtr.free_escalator_build_button", "/mtr escalator build"));
		message.append(" ").append(button("gui.mtr.free_escalator_undo_button", "/mtr escalator undo"));
		message.append(" ").append(button("gui.mtr.free_escalator_clear_button", "/mtr escalator clear"));
		player.displayClientMessage(message, false);
	}

	public static void undoMark(Player player) {
		final List<double[]> marks = MARKS.get(player.getUUID());
		if (marks == null || marks.isEmpty()) {
			player.displayClientMessage(Text.translatable("gui.mtr.free_escalator_no_points"), false);
			return;
		}
		marks.remove(marks.size() - 1);
		player.displayClientMessage(Text.translatable("gui.mtr.free_escalator_point_undone", marks.size() + 1), false);
	}

	public static void clearMarks(Player player) {
		MARKS.remove(player.getUUID());
		player.displayClientMessage(Text.translatable("gui.mtr.free_escalator_points_cleared"), false);
	}

	/** The points the player marked so far, for drawing them */
	public static List<double[]> getMarks(UUID player) {
		return MARKS.getOrDefault(player, Collections.emptyList());
	}

	/** Builds an escalator through the points the player marked; the marks are cleared if it was built */
	public static boolean build(Level world, Player player) {
		final List<double[]> marks = MARKS.getOrDefault(player.getUUID(), Collections.emptyList());
		final int[] kept = {0};
		final String error = build(world, marks, 1, SPEEDS[0], TileEntityFreeEscalator.MODE_UP, null, kept);
		if (error != null) {
			player.displayClientMessage(errorMessage(error), false);
			return false;
		}
		final FreeEscalatorPath path = FreeEscalatorPath.build(marks, 1).path;
		MARKS.remove(player.getUUID());
		player.displayClientMessage(Text.translatable("gui.mtr.free_escalator_built",
				Math.round(path.length * 10) / 10D,
				Math.round((path.height(path.length) - path.height(0)) * 10) / 10D,
				Text.translatable(path.stepped ? "gui.mtr.free_escalator_kind_escalator" : "gui.mtr.free_escalator_kind_walkway"))
				.append(kept[0] > 0 ? Text.translatable("gui.mtr.free_escalator_kept_blocks", kept[0]) : Text.literal("")), false);
		return true;
	}

	/**
	 * Puts an escalator into the world, or says why not as "key|argument|...". An escalator being changed (replacing,
	 * its first block) may take its own blocks again; the old blocks it doesn't use are taken away.
	 * <p>
	 * Blocks already there where the surface passes (other than air and what can be replaced) are left as they are and
	 * counted in kept[0]: where the run meets a floor at a point marked on the floor's edge block, the steps go under the
	 * floor as they go under a comb plate, and people step from the one onto the other.
	 */
	private static String build(Level world, List<double[]> points, double width, double speed, int mode, TileEntityFreeEscalator replacing, int[] kept) {
		final FreeEscalatorPath.Result result = FreeEscalatorPath.build(points, width);
		if (result.path == null) {
			final StringBuilder error = new StringBuilder("gui.mtr.free_escalator_").append(result.error);
			for (final Object argument : result.arguments) {
				error.append('|').append(argument);
			}
			return error.append(result.arguments.length == 0 ? "|" : "").toString();
		}
		final Set<Long> own = new HashSet<>();
		if (replacing != null) {
			for (final long cell : replacing.getCells()) {
				own.add(cell);
			}
		}
		final List<int[]> allCells = result.path.cells();
		if (allCells.isEmpty()) {
			return "gui.mtr.free_escalator_too_short|";
		}
		final List<int[]> cells = new ArrayList<>();
		BlockPos firstKept = null;
		for (final int[] cell : allCells) {
			final BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
			final BlockState state = world.getBlockState(pos);
			if (own.contains(pos.asLong()) || state.isAir() || state.canBeReplaced()) {
				cells.add(cell);
			} else if (firstKept == null) {
				firstKept = pos;
			}
		}
		kept[0] = allCells.size() - cells.size();
		if (cells.isEmpty()) {
			return "gui.mtr.free_escalator_blocked|" + firstKept.getX() + "|" + firstKept.getY() + "|" + firstKept.getZ();
		}

		final BlockPos headPos = replacing != null && containsCell(cells, replacing.getBlockPos()) ? replacing.getBlockPos() : new BlockPos(cells.get(0)[0], cells.get(0)[1], cells.get(0)[2]);
		long newId = replacing != null ? replacing.getId() : 0;
		while (newId == 0) {
			newId = RANDOM.nextLong();
		}
		final long[] cellLongs = new long[cells.size()];
		final Set<Long> placed = new HashSet<>();
		removing = true;
		try {
			for (int i = 0; i < cells.size(); i++) {
				final int[] cell = cells.get(i);
				final BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
				cellLongs[i] = pos.asLong();
				placed.add(pos.asLong());
				if (!(world.getBlockState(pos).getBlock() instanceof BlockFreeEscalator)) {
					world.setBlock(pos, Blocks.FREE_ESCALATOR.get().defaultBlockState(), 3);
				}
				final BlockEntity blockEntity = world.getBlockEntity(pos);
				if (blockEntity instanceof TileEntityFreeEscalator) {
					((TileEntityFreeEscalator) blockEntity).setCell(newId, headPos, result.path.columnHeights(cell[0], cell[1], cell[2]));
				}
			}
			// Blocks of the old shape not in the new one
			for (final long cell : own) {
				if (!placed.contains(cell) && world.isLoaded(BlockPos.of(cell))) {
					world.removeBlock(BlockPos.of(cell), false);
				}
			}
		} finally {
			removing = false;
		}
		final BlockEntity head = world.getBlockEntity(headPos);
		if (head instanceof TileEntityFreeEscalator) {
			((TileEntityFreeEscalator) head).setEscalator(points, width, speed, mode, cellLongs);
		}
		return null;
	}

	private static boolean containsCell(List<int[]> cells, BlockPos pos) {
		for (final int[] cell : cells) {
			if (cell[0] == pos.getX() && cell[1] == pos.getY() && cell[2] == pos.getZ()) {
				return true;
			}
		}
		return false;
	}

	// ---- Taking away ----

	/**
	 * A block of an escalator going: the whole escalator goes. Only blocks in loaded chunks are taken away now (no chunk is
	 * loaded for it); the escalator is put on the dimension's list of those taken away, and its blocks elsewhere go when
	 * their chunk loads.
	 */
	public static void onRemoved(Level world, BlockPos pos, TileEntityFreeEscalator blockEntity) {
		if (removing || world.isClientSide) {
			return;
		}
		final long id = blockEntity.getId();
		final FreeEscalatorRemovals removals = FreeEscalatorRemovals.getInstance(world);
		if (removals != null) {
			removals.add(id);
		}
		final Set<Long> cells = new HashSet<>();
		final Set<Long> loaded = id == 0 ? null : LOADED.getOrDefault(world, Collections.emptyMap()).get(id);
		if (loaded != null) {
			cells.addAll(loaded);
		}
		// Escalators from before ids, or blocks not known yet: the first block's list, if it is loaded
		final BlockPos headPos = blockEntity.getHead();
		if (world.isLoaded(headPos)) {
			final TileEntityFreeEscalator head = headOf(world, blockEntity);
			if (head != null && (id == 0 || head.getId() == id)) {
				for (final long cell : head.getCells()) {
					cells.add(cell);
				}
			}
		}
		removing = true;
		try {
			for (final long cell : cells) {
				final BlockPos cellPos = BlockPos.of(cell);
				if (!cellPos.equals(pos) && world.isLoaded(cellPos) && world.getBlockEntity(cellPos) instanceof TileEntityFreeEscalator && ((TileEntityFreeEscalator) world.getBlockEntity(cellPos)).getId() == id) {
					world.removeBlock(cellPos, false);
				}
			}
		} finally {
			removing = false;
		}
	}

	// ---- Loaded blocks and checks ----

	/** A block's block entity loaded (or its escalator changed): known by its escalator, and checked on the next tick */
	public static void onLoaded(Level world, TileEntityFreeEscalator blockEntity) {
		if (world == null || world.isClientSide || blockEntity.getId() == 0) {
			return;
		}
		LOADED.computeIfAbsent(world, key -> new HashMap<>()).computeIfAbsent(blockEntity.getId(), key -> new HashSet<>()).add(blockEntity.getBlockPos().asLong());
		TO_CHECK.add(new Object[]{world, blockEntity.getBlockPos().immutable(), blockEntity.getId()});
	}

	public static void onUnloaded(Level world, TileEntityFreeEscalator blockEntity) {
		if (world == null || world.isClientSide || blockEntity.getId() == 0) {
			return;
		}
		final Map<Long, Set<Long>> byId = LOADED.get(world);
		final Set<Long> cells = byId == null ? null : byId.get(blockEntity.getId());
		if (cells != null) {
			cells.remove(blockEntity.getBlockPos().asLong());
			if (cells.isEmpty()) {
				byId.remove(blockEntity.getId());
			}
		}
	}

	/** The server's tick: the blocks loaded since the last one checked */
	public static void serverTick() {
		if (TO_CHECK.isEmpty()) {
			return;
		}
		final List<Object[]> checks = new ArrayList<>(TO_CHECK);
		TO_CHECK.clear();
		for (final Object[] check : checks) {
			try {
				check((Level) check[0], (BlockPos) check[1], (Long) check[2]);
			} catch (Exception e) {
				LOGGER.error("Free escalator: checking a block at {} failed", check[1], e);
			}
		}
	}

	/**
	 * A block of an escalator just loaded goes if its escalator was taken away, or if its first block, loaded, is not
	 * there, belongs to another escalator or doesn't list it (left over from a crash, or from a narrower rebuild). A first block not loaded is not loaded for it.
	 */
	public static void check(Level world, BlockPos pos, long id) {
		if (!world.isLoaded(pos)) {
			return;
		}
		final BlockEntity blockEntity = world.getBlockEntity(pos);
		if (!(blockEntity instanceof TileEntityFreeEscalator) || ((TileEntityFreeEscalator) blockEntity).getId() != id) {
			return;
		}
		final FreeEscalatorRemovals removals = FreeEscalatorRemovals.getInstance(world);
		boolean gone = removals != null && removals.contains(id);
		final BlockPos headPos = ((TileEntityFreeEscalator) blockEntity).getHead();
		if (!gone && world.isLoaded(headPos)) {
			final BlockEntity head = world.getBlockEntity(headPos);
			gone = !(head instanceof TileEntityFreeEscalator) || ((TileEntityFreeEscalator) head).getId() != id || !((TileEntityFreeEscalator) head).isHead()
					|| Arrays.stream(((TileEntityFreeEscalator) head).getCells()).noneMatch(cell -> cell == pos.asLong());
		}
		if (gone) {
			if (removals != null) {
				removals.add(id);
			}
			removing = true;
			try {
				world.removeBlock(pos, false);
			} finally {
				removing = false;
			}
		}
	}

	/** All three coordinates are numbers */
	public static boolean isFinite(double[] point) {
		return point.length == 3 && Double.isFinite(point[0]) && Double.isFinite(point[1]) && Double.isFinite(point[2]);
	}

	private static TileEntityFreeEscalator headOf(Level world, TileEntityFreeEscalator blockEntity) {
		if (blockEntity.isHead()) {
			return blockEntity;
		}
		if (!world.isLoaded(blockEntity.getHead())) {
			return null;
		}
		final BlockEntity head = world.getBlockEntity(blockEntity.getHead());
		return head instanceof TileEntityFreeEscalator && ((TileEntityFreeEscalator) head).isHead() ? (TileEntityFreeEscalator) head : null;
	}

	private static TileEntityFreeEscalator headAt(Level world, BlockPos pos) {
		final BlockEntity blockEntity = world.getBlockEntity(pos);
		return blockEntity instanceof TileEntityFreeEscalator ? headOf(world, (TileEntityFreeEscalator) blockEntity) : null;
	}

	// ---- Brush and settings ----

	/** The brush on an escalator: up, down, stopped, up again */
	public static void cycleMode(Level world, BlockPos pos, Player player) {
		final TileEntityFreeEscalator head = headAt(world, pos);
		if (head == null) {
			return;
		}
		final int mode = head.getMode() == TileEntityFreeEscalator.MODE_UP ? TileEntityFreeEscalator.MODE_DOWN : head.getMode() == TileEntityFreeEscalator.MODE_DOWN ? TileEntityFreeEscalator.MODE_STOP : TileEntityFreeEscalator.MODE_UP;
		head.setMode(mode);
		player.displayClientMessage(Text.translatable(modeKey(mode)), true);
	}

	private static String modeKey(int mode) {
		return mode == TileEntityFreeEscalator.MODE_UP ? "gui.mtr.free_escalator_up" : mode == TileEntityFreeEscalator.MODE_DOWN ? "gui.mtr.free_escalator_down" : "gui.mtr.free_escalator_stopped";
	}

	/** Sneaking with the brush on an escalator: its settings, as buttons in the chat */
	public static void showSettings(Level world, BlockPos pos, Player player) {
		final TileEntityFreeEscalator head = headAt(world, pos);
		if (head == null) {
			return;
		}
		final BlockPos headPos = head.getBlockPos();
		final String where = headPos.getX() + " " + headPos.getY() + " " + headPos.getZ();
		final MutableComponent message = Text.translatable("gui.mtr.free_escalator_settings", head.getSpeed(), (int) head.getWidth(), Text.translatable(modeKey(head.getMode())));
		message.append("\n").append(Text.translatable("gui.mtr.free_escalator_speed_label"));
		for (final double speed : SPEEDS) {
			message.append(" ").append(button(Text.literal("[" + speed + " m/s]"), "/mtr escalator speed " + where + " " + speed, speed == head.getSpeed()));
		}
		message.append("\n").append(Text.translatable("gui.mtr.free_escalator_width_label"));
		for (final double width : WIDTHS) {
			message.append(" ").append(button(Text.translatable("gui.mtr.free_escalator_width_button", (int) width), "/mtr escalator width " + where + " " + (int) width, width == head.getWidth()));
		}
		player.displayClientMessage(message, false);
	}

	/** Speed from the settings: null if done, else the key of what is wrong */
	public static String setSpeed(Level world, BlockPos headPos, double speed) {
		final TileEntityFreeEscalator head = headAt(world, headPos);
		if (head == null) {
			return "gui.mtr.free_escalator_not_found";
		}
		head.setSpeed(Math.max(SPEEDS[0], Math.min(SPEEDS[SPEEDS.length - 1], speed)));
		return null;
	}

	/** Width from the settings: the escalator is built again, wider or narrower, if there is room */
	public static String setWidth(Level world, BlockPos headPos, double width) {
		final TileEntityFreeEscalator head = headAt(world, headPos);
		if (head == null) {
			return "gui.mtr.free_escalator_not_found|";
		}
		return build(world, new ArrayList<>(head.getPoints()), width, head.getSpeed(), head.getMode(), head, new int[1]);
	}

	/** An error from {@link #setWidth} as a message */
	public static Component errorMessage(String error) {
		final String[] parts = error.split("\\|", -1);
		return Text.translatable(parts[0], (Object[]) Arrays.copyOfRange(parts, 1, parts.length)).withStyle(ChatFormatting.RED);
	}

	// ---- Carrying ----

	/** The first block's tick, on both sides; an error is logged once and skipped, never crashing the game */
	public static void tick(Level world, TileEntityFreeEscalator head) {
		try {
			carry(world, head);
		} catch (Exception e) {
			if (!tickFailed) {
				tickFailed = true;
				LOGGER.error("Free escalator at {}: carrying failed", head.getBlockPos(), e);
			}
		}
	}

	private static void carry(Level world, TileEntityFreeEscalator head) {
		if (head.getMode() == TileEntityFreeEscalator.MODE_STOP) {
			return;
		}
		final FreeEscalatorPath path = head.getPath();
		if (path == null) {
			return;
		}
		final double speed = path.horizontalSpeed(head.getSpeed()) * (head.getMode() == TileEntityFreeEscalator.MODE_UP ? 1 : -1);
		final double reach = path.width / 2 + 0.3;
		final AABB box = bounds(path).inflate(0.5, 1, 0.5);
		for (final LivingEntity entity : world.getEntitiesOfClass(LivingEntity.class, box, entity -> carries(world, entity))) {
			final double distance = path.along(entity.getX(), entity.getZ());
			if (distance < 0 || distance > path.length || Math.abs(path.across(entity.getX(), entity.getZ())) > reach) {
				continue;
			}
			final double stand = path.standHeight(distance);
			final double feet = entity.getY();
			// Standing on it (not jumping off it); a wide mob stands on the highest part under it
			if (feet < stand - 0.35 || feet > stand + 0.35 + entity.getBbWidth() / 2 * path.maxGrade || entity.getDeltaMovement().y > 0.1) {
				continue;
			}
			// Along the run, and up or down with the surface: kept on it rather than left to step up (a mob without AI
			// never falls back onto the ground to step from), or onto what is there where it will be (a floor the run
			// goes under at its end), at most a step up
			final double next = Math.max(0, Math.min(path.length, distance + speed));
			final double dx = path.directionX * speed;
			final double dz = path.directionZ * speed;
			double top = path.standHeight(next);
			final AABB moved = entity.getBoundingBox().move(dx, 0, dz);
			for (final VoxelShape shape : world.getBlockCollisions(entity, new AABB(moved.minX, feet - 0.5, moved.minZ, moved.maxX, feet + 0.6, moved.maxZ))) {
				final double shapeTop = shape.max(Direction.Axis.Y);
				if (shapeTop <= feet + 0.6) {
					top = Math.max(top, shapeTop);
				}
			}
			final double rise = Math.max(-0.2, Math.min(0.6, top + 0.001 - feet));
			entity.move(MoverType.SELF, new Vec3(dx, rise, dz));
		}
	}

	/** On the server mobs, on a client only its own player */
	private static boolean carries(Level world, Entity entity) {
		if (entity.isSpectator() || entity.isPassenger() || entity.noPhysics) {
			return false;
		}
		if (entity instanceof Player) {
			return world.isClientSide && ((Player) entity).isLocalPlayer() && !((Player) entity).getAbilities().flying;
		}
		return !world.isClientSide;
	}

	public static AABB bounds(FreeEscalatorPath path) {
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (final double distance : new double[]{0, path.length}) {
			for (final double side : new double[]{-path.width / 2, path.width / 2}) {
				minX = Math.min(minX, path.worldX(distance, side));
				maxX = Math.max(maxX, path.worldX(distance, side));
				minZ = Math.min(minZ, path.worldZ(distance, side));
				maxZ = Math.max(maxZ, path.worldZ(distance, side));
			}
		}
		return new AABB(minX, path.height(0) - 1, minZ, maxX, path.height(path.length) + 2, maxZ);
	}

	// ---- Chat buttons ----

	private static Component button(String key, String command) {
		return button(Text.translatable(key), command, false);
	}

	private static Component button(MutableComponent text, String command, boolean current) {
		return text.setStyle(Style.EMPTY.withColor(current ? ChatFormatting.GREEN : ChatFormatting.AQUA).withUnderlined(!current)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command))));
	}
}
