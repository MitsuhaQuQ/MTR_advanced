package mtr.data;

import mtr.mappings.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import transitsim.fare.FareLevels;
import transitsim.fare.FareTrip;

import java.util.Locale;

/**
 * Ticket gates (design document 6.13, 0.68.0). A trip runs from entering at one gate to leaving at another and is charged
 * by distance along the fare path ({@link FareTrip}); a gate stands for the fare groups of the station it is in
 * ({@link FareData#getGate}). Entering records the gate and the time. Balances and records are
 * kept on the scoreboard by the player's UUID. A balance kept by name, as before 0.68.0 and as commands such as
 * {@code /scoreboard players add <name> mtr_balance} still keep it, is added to it each time it is used.
 * <p>
 * Other mods: the public members are kept, as YJCM's enquiry and subsidy machines call them. YJCM's
 * {@code MixinTicketBarrier} injects into {@code onExit(Station, Player, Score, Score, boolean)} with
 * {@code defaultRequire: 1}, so a method of that name and signature must stay (it is never called); its fare saver's
 * discount is given here instead ({@link #applyFareSaver}), on the fare by distance.
 */
public class TicketSystem {

	public static int getAddAmount(int index) {
		return (int) Math.ceil(Math.pow(2, index) * (EMERALD_TO_DOLLAR + index));
	}

	public static final String BALANCE_OBJECTIVE = "mtr_balance";
	public static final int ADD_BALANCE_OPTIONS = 10;
	private static final int EMERALD_TO_DOLLAR = 10;
	/**
	 * Non-zero while the player is inside, as before.
	 */
	private static final String ENTRY_ZONE_OBJECTIVE = "mtr_entry_zone";
	/**
	 * The position of the gate entered at, in two halves, and the minute entered (0.68.0).
	 */
	private static final String ENTRY_GATE_HIGH_OBJECTIVE = "mtr_entry_gate_hi";
	private static final String ENTRY_GATE_LOW_OBJECTIVE = "mtr_entry_gate_lo";
	private static final String ENTRY_MINUTE_OBJECTIVE = "mtr_entry_minute";
	private static final int EVASION_FINE = 500;

	public static EnumTicketBarrierOpen passThrough(Level world, BlockPos pos, Player player, boolean isEntrance, boolean isExit, SoundEvent entrySound, SoundEvent entrySoundConcessionary, SoundEvent exitSound, SoundEvent exitSoundConcessionary, SoundEvent failSound, boolean remindIfNoRecord) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			return EnumTicketBarrierOpen.CLOSED;
		}

		final Station station = RailwayData.getStation(railwayData.stations, railwayData.dataCache, pos);
		if (station == null) {
			return EnumTicketBarrierOpen.CLOSED;
		}

		addObjectivesIfMissing(world);

		final Score balanceScore = getPlayerScore(world, player, BALANCE_OBJECTIVE);
		final Score entryZoneScore = getPlayerScore(world, player, ENTRY_ZONE_OBJECTIVE);

		final boolean isEntering;
		if (isEntrance && isExit) {
			isEntering = entryZoneScore.getScore() == 0;
		} else {
			isEntering = isEntrance;
		}

		// A gate stands for the groups of the station whose area it is in
		final FareTrip.Gate gate = FareData.getGate(railwayData.dataCache, station);
		final boolean canOpen;
		if (isEntering) {
			canOpen = onEnter(world, railwayData, station, gate, pos, player, balanceScore, entryZoneScore, remindIfNoRecord);
		} else {
			canOpen = chargeOnExit(world, railwayData, station, gate, player, balanceScore, entryZoneScore, remindIfNoRecord);
		}

		if (canOpen) {
			world.playSound(null, pos, isEntering ? entrySound : exitSound, SoundSource.BLOCKS, 1, 1);
		} else if (failSound != null) {
			world.playSound(null, pos, failSound, SoundSource.BLOCKS, 1, 1);
		}

		return canOpen ? EnumTicketBarrierOpen.OPEN : EnumTicketBarrierOpen.CLOSED;
	}

	public static void addObjectivesIfMissing(Level world) {
		addObjective(world, BALANCE_OBJECTIVE, "Balance");
		addObjective(world, ENTRY_ZONE_OBJECTIVE, "Entry Zone");
		addObjective(world, ENTRY_GATE_HIGH_OBJECTIVE, "Entry Gate");
		addObjective(world, ENTRY_GATE_LOW_OBJECTIVE, "Entry Gate");
		addObjective(world, ENTRY_MINUTE_OBJECTIVE, "Entry Minute");
	}

	private static void addObjective(Level world, String name, String displayName) {
		if (world.getScoreboard().getObjective(name) == null) {
			try {
				world.getScoreboard().addObjective(name, ObjectiveCriteria.DUMMY, Text.literal(displayName), ObjectiveCriteria.RenderType.INTEGER);
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * @return the player's score, kept by their UUID. A balance kept by their name (before 0.68.0, or added by a command
	 * since) is added to it and the name's score removed; any other score kept by name is moved over if there is none by
	 * UUID yet.
	 */
	public static Score getPlayerScore(Level world, Player player, String objectiveName) {
		final Scoreboard scoreboard = world.getScoreboard();
		final Objective objective = scoreboard.getObjective(objectiveName);
		final String holder = player.getUUID().toString();
		final String name = player.getGameProfile().getName();
		if (objective != null && !name.equals(holder) && scoreboard.hasPlayerScore(name, objective)) {
			final boolean hasHolder = scoreboard.hasPlayerScore(holder, objective);
			if (BALANCE_OBJECTIVE.equals(objectiveName) || !hasHolder) {
				final int byName = scoreboard.getOrCreatePlayerScore(name, objective).getScore();
				scoreboard.resetPlayerScore(name, objective);
				final Score score = scoreboard.getOrCreatePlayerScore(holder, objective);
				score.setScore(mergeScore(hasHolder ? score.getScore() : 0, byName, BALANCE_OBJECTIVE.equals(objectiveName)));
			}
		}
		return scoreboard.getOrCreatePlayerScore(holder, objective);
	}

	/**
	 * @return a balance kept by name added to the one kept by UUID, kept within an int; any other score kept by name
	 * replaces it
	 */
	static int mergeScore(int byHolder, int byName, boolean add) {
		return add ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) byHolder + byName)) : byName;
	}

	private static boolean onEnter(Level world, RailwayData railwayData, Station station, FareTrip.Gate gate, BlockPos pos, Player player, Score balanceScore, Score entryZoneScore, boolean remindIfNoRecord) {
		if (entryZoneScore.getScore() != 0) {
			if (remindIfNoRecord) {
				player.displayClientMessage(Text.translatable("gui.mtr.already_entered"), true);
				return false;
			} else {
				entryZoneScore.setScore(0);
				balanceScore.add(-EVASION_FINE);
			}
		}

		if (balanceScore.getScore() >= 0) {
			entryZoneScore.setScore(1);
			getPlayerScore(world, player, ENTRY_GATE_HIGH_OBJECTIVE).setScore((int) (pos.asLong() >> 32));
			getPlayerScore(world, player, ENTRY_GATE_LOW_OBJECTIVE).setScore((int) pos.asLong());
			getPlayerScore(world, player, ENTRY_MINUTE_OBJECTIVE).setScore((int) (System.currentTimeMillis() / 60000));
			player.displayClientMessage(Text.translatable("gui.mtr.enter_barrier", stationText(railwayData, station, gate), balanceScore.getScore()), true);
			return true;
		} else {
			player.displayClientMessage(Text.translatable("gui.mtr.insufficient_balance", balanceScore.getScore()), true);
			return false;
		}
	}

	/**
	 * Kept with the signature it had before 0.68.0 for YJCM's {@code MixinTicketBarrier}, which injects at its head and
	 * fails to load without it. Never called: exits are charged by {@link #chargeOnExit}, which also gives YJCM's fare
	 * saver discount.
	 */
	@SuppressWarnings("unused")
	private static boolean onExit(Station station, Player player, Score balanceScore, Score entryZoneScore, boolean remindIfNoRecord) {
		return false;
	}

	/**
	 * YJCM's fare saver (1.2.x): a player who used one has a discount, by UUID, in {@code Joban.discountMap}, taken off
	 * the next fare paid (a negative one, its "sarcasm" setting, is added). As YJCM did, the discount is used up and
	 * told in the chat. Without YJCM nothing happens.
	 *
	 * @return the fare after the discount
	 */
	private static int applyFareSaver(Player player, int fare) {
		final it.unimi.dsi.fastutil.objects.Object2IntMap<java.util.UUID> discounts = getFareSaverDiscounts();
		if (discounts == null || !discounts.containsKey(player.getUUID())) {
			return fare;
		}
		final int discount = discounts.removeInt(player.getUUID());
		final int saved = fareSaverSaving(discount, fare);
		player.displayClientMessage(Text.translatable(discount >= 0 ? "gui.jsblock.faresaver.saved" : "gui.jsblock.faresaver.saved_sarcasm", saved), false);
		return fare - saved;
	}

	/**
	 * @return what a fare saver's discount takes off a fare, as YJCM worked it out: no more than the fare
	 */
	static int fareSaverSaving(int discount, int fare) {
		return Math.min(discount, fare);
	}

	private static boolean fareSaverLooked;
	private static it.unimi.dsi.fastutil.objects.Object2IntMap<java.util.UUID> fareSaverDiscounts;

	@SuppressWarnings("unchecked")
	private static it.unimi.dsi.fastutil.objects.Object2IntMap<java.util.UUID> getFareSaverDiscounts() {
		if (!fareSaverLooked) {
			fareSaverLooked = true;
			for (final String className : new String[]{"fabric.com.jsblock.Joban", "forge.com.jsblock.Joban", "com.jsblock.Joban"}) {
				try {
					final Object map = Class.forName(className).getField("discountMap").get(null);
					if (map instanceof it.unimi.dsi.fastutil.objects.Object2IntMap) {
						fareSaverDiscounts = (it.unimi.dsi.fastutil.objects.Object2IntMap<java.util.UUID>) map;
						break;
					}
				} catch (Throwable ignored) {
				}
			}
		}
		return fareSaverDiscounts;
	}

	private static boolean chargeOnExit(Level world, RailwayData railwayData, Station station, FareTrip.Gate gate, Player player, Score balanceScore, Score entryZoneScore, boolean remindIfNoRecord) {
		if (entryZoneScore.getScore() == 0) {
			if (remindIfNoRecord) {
				player.displayClientMessage(Text.translatable("gui.mtr.already_exited"), true);
				return false;
			}
			balanceScore.add(-EVASION_FINE);
			player.displayClientMessage(Text.translatable("gui.mtr.exit_barrier", stationText(railwayData, station, gate), EVASION_FINE, balanceScore.getScore()), true);
			return true;
		}

		final Score gateHigh = getPlayerScore(world, player, ENTRY_GATE_HIGH_OBJECTIVE);
		final Score gateLow = getPlayerScore(world, player, ENTRY_GATE_LOW_OBJECTIVE);
		final Score minute = getPlayerScore(world, player, ENTRY_MINUTE_OBJECTIVE);
		final long entryPos = ((long) gateHigh.getScore() << 32) | (gateLow.getScore() & 0xFFFFFFFFL);
		final long minutesInside = Math.max(0, System.currentTimeMillis() / 60000 - minute.getScore());
		entryZoneScore.setScore(0);
		gateHigh.setScore(0);
		gateLow.setScore(0);
		minute.setScore(0);

		final long[] blocks = new long[1];
		final int fare = applyFareSaver(player, getTripFare(railwayData, entryPos == 0 ? null : BlockPos.of(entryPos), gate, minutesInside, blocks));
		balanceScore.add(-fare);
		player.displayClientMessage(Text.translatable("gui.mtr.exit_barrier", stationText(railwayData, station, gate) + kilometresText(blocks[0]), fare, balanceScore.getScore()), true);
		return true;
	}

	/**
	 * The fare by distance of a trip between two gates, for other mods' gates with balances of their own (Metropolis,
	 * {@code MetropolisTurnstileMixin}).
	 *
	 * @param entryPos      the gate entered at
	 * @param exitPos       the gate left at
	 * @param minutesInside how long from entering to leaving
	 * @return the fare, or -1 if the gate left at is in no station
	 */
	public static int getTripFare(Level world, BlockPos entryPos, BlockPos exitPos, long minutesInside) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		final Station station = railwayData == null ? null : RailwayData.getStation(railwayData.stations, railwayData.dataCache, exitPos);
		return station == null ? -1 : getTripFare(railwayData, entryPos, FareData.getGate(railwayData.dataCache, station), Math.max(0, minutesInside), new long[1]);
	}

	/**
	 * @return the minute now, as entering is recorded in
	 */
	public static long getMinute() {
		return System.currentTimeMillis() / 60000;
	}

	/**
	 * @param entryPos the gate entered at, or null if not known (a record from before 0.68.0)
	 * @param blocks   given the distance charged
	 * @return the fare from the gate entered at, as the station it is in is set now, to a gate: by distance, or the start
	 * fare of the group left by if not joined or started nowhere known
	 */
	private static int getTripFare(RailwayData railwayData, BlockPos entryPos, FareTrip.Gate gate, long minutesInside, long[] blocks) {
		final Station entryStation = entryPos == null ? null : RailwayData.getStation(railwayData.stations, railwayData.dataCache, entryPos);
		final FareTrip.Gate entryGate = entryStation == null ? null : FareData.getGate(railwayData.dataCache, entryStation);
		if (entryGate != null) {
			final FareTrip.Result result = FareTrip.charge(railwayData.getFareNetwork(), group -> railwayData.dataCache.networkSettings.fareLevels.get(group), entryGate, gate, minutesInside);
			if (result.fare >= 0) {
				blocks[0] = result.path == null ? 0 : result.path.getBlocks();
				return result.fare;
			}
		}
		final String group = gate.groups.isEmpty() ? null : gate.groups.iterator().next();
		return FareData.getLevels(railwayData, group).getSameStationFare(0);
	}

	private static String kilometresText(long blocks) {
		return blocks > 0 ? String.format(Locale.ENGLISH, " %.1f km", blocks / (double) FareLevels.BLOCKS_PER_KILOMETRE) : "";
	}

	private static String stationText(RailwayData railwayData, Station station, FareTrip.Gate gate) {
		final String groups = FareData.getGroupNames(railwayData.dataCache, gate.groups);
		return IGui.formatStationName(station.name) + (groups.isEmpty() ? "" : " (" + groups + ")");
	}

	public enum EnumTicketBarrierOpen implements StringRepresentable {

		/**
		 * {@link #OPEN_CONCESSIONARY} isn't used any more (0.68.0: creative players pay the same), but kept for other mods
		 * and saved block states.
		 */
		CLOSED("closed"), OPEN("open"), OPEN_CONCESSIONARY("open_concessionary");
		private final String name;

		EnumTicketBarrierOpen(String nameIn) {
			name = nameIn;
		}

		@Override
		public String getSerializedName() {
			return name;
		}

		public boolean isOpen() {
			return this != CLOSED;
		}
	}
}
