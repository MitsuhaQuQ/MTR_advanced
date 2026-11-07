package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.DyeColor;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

public class SignalBlocks {

	private int version;

	private final Map<UUID, Set<SignalBlock>> railToSignalBlocks = new HashMap<>();
	private Map<UUID, Long> zones = Collections.emptyMap();
	/**
	 * The signal blocks cut into block sections, made again when the blocks or the interlocking colour change.
	 */
	private transitsim.network.BlockSections blockSections;
	private static final transitsim.network.BlockSections.RailInfo UNKNOWN_RAILS = new transitsim.network.BlockSections.RailInfo() {
		@Override
		public double getLength(UUID rail) {
			return 0;
		}

		@Override
		public float getMaxSpeed(UUID rail) {
			return 0;
		}
	};
	private int sectionsVersion = -1;
	private DyeColor sectionsColor;
	private int sectionsRailsVersion;
	private int zonesVersion = -1;
	private DyeColor zonesColor;
	public final List<SignalBlock> signalBlocks = new ArrayList<>();

	public long add(long id, DyeColor color, UUID rail) {
		final List<SignalBlock> connectedSignalBlocks = new ArrayList<>();
		signalBlocks.forEach(signalBlock -> {
			if (signalBlock.color == color && signalBlock.isConnected(rail)) {
				connectedSignalBlocks.add(signalBlock);
			}
		});

		if (connectedSignalBlocks.isEmpty()) {
			final SignalBlock newSignalBlock = new SignalBlock(id, color, rail);
			signalBlocks.add(newSignalBlock);
			writeCache();
			return newSignalBlock.id;
		} else {
			Collections.sort(connectedSignalBlocks);
			final SignalBlock firstSignalBlock = connectedSignalBlocks.remove(0);
			firstSignalBlock.rails.add(rail);
			connectedSignalBlocks.forEach(signalBlock -> firstSignalBlock.rails.addAll(signalBlock.rails));
			signalBlocks.removeIf(connectedSignalBlocks::contains);
			writeCache();
			return 0;
		}
	}

	public long remove(long id, DyeColor color, UUID rail) {
		SignalBlock connectedSignalBlock = null;
		for (final SignalBlock signalBlock : signalBlocks) {
			if (signalBlock.color == color && signalBlock.isConnected(rail)) {
				connectedSignalBlock = signalBlock;
				break;
			}
		}

		if (connectedSignalBlock != null) {
			signalBlocks.remove(connectedSignalBlock);
			connectedSignalBlock.rails.remove(rail);

			if (!connectedSignalBlock.rails.isEmpty()) {
				final List<UUID> rails = new ArrayList<>(connectedSignalBlock.rails);
				Collections.sort(rails);
				add(connectedSignalBlock.id, color, rails.remove(0));

				long returnId = 0;
				for (final UUID existingRail : rails) {
					final long newId = add(id, color, existingRail);
					if (newId != connectedSignalBlock.id) {
						returnId = newId;
					}
				}

				writeCache();
				return returnId;
			}
		}

		writeCache();
		return 0;
	}

	/**
	 * Takes a rail in one piece; see {@link #occupy(UUID, UUID, List, long)}.
	 */
	public void occupy(UUID currentRail, List<Map<UUID, Long>> trainPositions, long trainId) {
		occupy(currentRail, currentRail, trainPositions, trainId);
	}

	/**
	 * Takes a block section of a rail a train is on, and with it the rest of the section of each signal block it is in,
	 * unless something else has part of that already.
	 *
	 * @param unit the rail's key, or the key of the piece of a long rail ({@code BlockSections})
	 */
	public void occupy(UUID currentRail, UUID unit, List<Map<UUID, Long>> trainPositions, long trainId) {
		if (trainPositions.size() < 2) {
			return;
		}

		// The section under a train always counts, or a train that entered a taken signal block would be invisible to others
		trainPositions.get(1).putIfAbsent(unit, trainId);
		unitRails.putIfAbsent(unit, currentRail);

		final Set<UUID> railsToAdd = new HashSet<>();
		railsToAdd.add(unit);

		if (railToSignalBlocks.containsKey(currentRail)) {
			railToSignalBlocks.get(currentRail).forEach(signalBlock -> signalBlock.occupied = 2);
			// Not cut yet, the blocks are taken whole, as with rails of unknown length
			if (blockSections == null) {
				updateSections(DyeColor.MAGENTA, UNKNOWN_RAILS);
			}
			final List<UUID> section = blockSections == null ? null : blockSections.getSection(unit);
			if (section != null) {
				railsToAdd.addAll(section);
			}
		}

		for (final Map<UUID, Long> trainPositionsMap : trainPositions) {
			if (railsToAdd.stream().anyMatch(rail -> trainPositionsMap.containsKey(rail) && trainPositionsMap.get(rail) != trainId)) {
				return;
			}
		}

		railsToAdd.forEach(rail -> trainPositions.get(1).put(rail, trainId));
	}

	/**
	 * The rail of each unit trains took this tick, for telling clients which piece of which rail is taken.
	 */
	private final Map<UUID, UUID> unitRails = new HashMap<>();

	/**
	 * @return the rail and the number of the piece a unit trains have taken is, for the clients; a unit not known is a
	 * whole rail
	 */
	public Map.Entry<UUID, Integer> getPiece(UUID unit, transitsim.network.BlockSections.RailInfo railInfo) {
		final Map.Entry<UUID, Integer> inBlock = blockSections == null ? null : blockSections.getPiece(unit);
		if (inBlock != null) {
			return inBlock;
		}
		final UUID rail = unitRails.get(unit);
		if (rail != null) {
			final int count = transitsim.network.BlockSections.getPieceCount(railInfo.getLength(rail), railInfo.getMaxSpeed(rail));
			for (int k = 0; k < count; k++) {
				if (transitsim.network.BlockSections.getPieceKey(rail, k).equals(unit)) {
					return new AbstractMap.SimpleImmutableEntry<>(rail, k);
				}
			}
		}
		return new AbstractMap.SimpleImmutableEntry<>(unit, 0);
	}

	public void resetOccupied() {
		unitRails.clear();
		signalBlocks.forEach(signalBlock -> {
			if (signalBlock.isOccupied()) {
				signalBlock.occupied--;
			}
		});
	}

	public List<SignalBlock> getSignalBlocksAtTrack(UUID rail) {
		if (railToSignalBlocks.containsKey(rail)) {
			final List<SignalBlock> matchingSignalBlocks = new ArrayList<>(railToSignalBlocks.get(rail));
			matchingSignalBlocks.sort(Comparator.comparingInt(signalBlock -> signalBlock.color.ordinal()));
			return matchingSignalBlocks;
		} else {
			return new ArrayList<>();
		}
	}

	public boolean isOccupied(UUID rail) {
		if (railToSignalBlocks.containsKey(rail)) {
			return railToSignalBlocks.get(rail).stream().anyMatch(SignalBlock::isOccupied);
		} else {
			return false;
		}
	}

	public void getSignalBlockStatus(Map<Long, Boolean> signalBlockStatus, UUID rail) {
		if (railToSignalBlocks.containsKey(rail)) {
			railToSignalBlocks.get(rail).forEach(signalBlock -> signalBlockStatus.put(signalBlock.id, signalBlock.isOccupied()));
		}
	}

	public void writeSignalBlockStatus(Map<Long, Boolean> signalBlockStatus) {
		signalBlockStatus.forEach((id, occupied) -> signalBlocks.forEach(signalBlock -> {
			if (signalBlock.id == id) {
				signalBlock.occupied = occupied ? 2 : 0;
			}
		}));
	}

	public FriendlyByteBuf getValidationPacket(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final List<UUID> railsToRemove = new ArrayList<>();
		final List<DyeColor> colorsToRemove = new ArrayList<>();

		signalBlocks.forEach(signalBlock -> signalBlock.rails.forEach(rail -> {
			final BlockPos pos1 = BlockPos.of(rail.getLeastSignificantBits());
			final BlockPos pos2 = BlockPos.of(rail.getMostSignificantBits());
			if (!RailwayData.containsRail(rails, pos1, pos2)) {
				railsToRemove.add(rail);
				colorsToRemove.add(signalBlock.color);
			}
		}));

		if (railsToRemove.isEmpty()) {
			return null;
		} else {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeInt(railsToRemove.size());
			for (int i = 0; i < railsToRemove.size(); i++) {
				final DyeColor color = colorsToRemove.get(i);
				final UUID rail = railsToRemove.get(i);
				packet.writeLong(remove(0, color, rail));
				packet.writeInt(color.ordinal());
				packet.writeUUID(rail);
			}
			return packet;
		}
	}

	/**
	 * @return the rails of each signal block, for the component's simulator (G1)
	 */
	public List<List<UUID>> getBlockRails() {
		final List<List<UUID>> blockRails = new ArrayList<>();
		signalBlocks.forEach(signalBlock -> {
			final List<UUID> rails = new ArrayList<>(signalBlock.rails);
			rails.sort(Comparator.naturalOrder());
			blockRails.add(rails);
		});
		return blockRails;
	}

	/**
	 * @return the rails of each signal block by the block's ID, for the signal layer of the track sections
	 */
	public Map<Long, List<UUID>> getBlockRailsById() {
		final Map<Long, List<UUID>> blockRails = new TreeMap<>();
		signalBlocks.forEach(signalBlock -> blockRails.computeIfAbsent(signalBlock.id, key -> new ArrayList<>()).addAll(signalBlock.rails));
		return blockRails;
	}

	/**
	 * Works out the block sections of the signal blocks again if they have changed since: each block is one section, with
	 * every piece of its rails.
	 */
	public void updateSections(DyeColor interlockColor, transitsim.network.BlockSections.RailInfo railInfo) {
		updateSections(interlockColor, railInfo, 0);
	}

	/**
	 * @param railsVersion a number that changes when what is known of the rails does, as on a client getting the rails near it
	 */
	public void updateSections(DyeColor interlockColor, transitsim.network.BlockSections.RailInfo railInfo, int railsVersion) {
		if (blockSections == null || sectionsVersion != version || sectionsColor != interlockColor || sectionsRailsVersion != railsVersion) {
			sectionsRailsVersion = railsVersion;
			blockSections = new transitsim.network.BlockSections(getBlockRails(), railInfo);
			sectionsVersion = version;
			sectionsColor = interlockColor;
		}
	}

	/**
	 * @return the signal blocks as block sections, or null before {@link #updateSections}
	 */
	public transitsim.network.BlockSections getBlockSections() {
		return blockSections;
	}

	/**
	 * @return whether each signal block, in the order of {@link #getBlockRails}, is of this colour, an interlocking zone (L2)
	 */
	public List<Boolean> getInterlockFlags(DyeColor color) {
		final List<Boolean> flags = new ArrayList<>();
		signalBlocks.forEach(signalBlock -> flags.add(signalBlock.color == color));
		return flags;
	}

	/**
	 * The interlocking zones (L2): the signal blocks of this colour, which a route through a station throat takes as a whole.
	 * Worked out again only when the blocks or the colour change; a map once given out never changes, so it can be used
	 * away from the thread that changes the blocks.
	 *
	 * @return the zone of each rail in one, by the block's ID
	 */
	public synchronized Map<UUID, Long> getZones(DyeColor color) {
		if (zonesVersion != version || zonesColor != color) {
			final Map<UUID, Long> newZones = new HashMap<>();
			signalBlocks.forEach(signalBlock -> {
				if (signalBlock.color == color) {
					signalBlock.rails.forEach(rail -> newZones.putIfAbsent(rail, signalBlock.id));
				}
			});
			zones = Collections.unmodifiableMap(newZones);
			zonesVersion = version;
			zonesColor = color;
		}
		return zones;
	}

	/**
	 * @return a number that changes each time the signal blocks are worked out again
	 */
	public int getVersion() {
		return version;
	}

	public void writeCache() {
		version++;
		railToSignalBlocks.clear();
		signalBlocks.forEach(signalBlock -> signalBlock.rails.forEach(rail -> {
			if (!railToSignalBlocks.containsKey(rail)) {
				railToSignalBlocks.put(rail, new HashSet<>());
			}
			railToSignalBlocks.get(rail).add(signalBlock);
		}));
	}

	public static class SignalBlock extends NameColorDataBase {

		public final DyeColor color;
		private final Set<UUID> rails = new HashSet<>();
		private int occupied = 0;

		private static final String KEY_COLOR = "color";
		private static final String KEY_RAILS = "rails";

		private SignalBlock(long id, DyeColor color, UUID rail) {
			super(id);
			this.color = color;
			rails.add(rail);
		}

		public SignalBlock(Map<String, Value> map) {
			super(map);
			final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
			DyeColor savedColor;
			try {
				savedColor = DyeColor.values()[messagePackHelper.getInt(KEY_COLOR)];
			} catch (Exception e) {
				e.printStackTrace();
				savedColor = DyeColor.RED;
			}
			color = savedColor;

			map.get(KEY_RAILS).asArrayValue().forEach(value -> rails.add(UUID.fromString(value.asStringValue().asString())));
		}

		@Deprecated
		public SignalBlock(CompoundTag compoundTag) {
			super(compoundTag);
			DyeColor savedColor;
			try {
				savedColor = DyeColor.values()[compoundTag.getInt(KEY_COLOR)];
			} catch (Exception e) {
				e.printStackTrace();
				savedColor = DyeColor.RED;
			}
			color = savedColor;
			final CompoundTag compoundTagRails = compoundTag.getCompound(KEY_RAILS);
			compoundTagRails.getAllKeys().forEach(key -> rails.add(compoundTagRails.getUUID(key)));
		}

		public SignalBlock(FriendlyByteBuf packet) {
			super(packet);
			DyeColor savedColor;
			try {
				savedColor = DyeColor.values()[packet.readInt()];
			} catch (Exception e) {
				e.printStackTrace();
				savedColor = DyeColor.RED;
			}
			color = savedColor;
			final int railCount = packet.readInt();
			for (int i = 0; i < railCount; i++) {
				rails.add(packet.readUUID());
			}
		}

		@Override
		public void toMessagePack(MessagePacker messagePacker) throws IOException {
			super.toMessagePack(messagePacker);

			messagePacker.packString(KEY_COLOR).packInt(color.ordinal());
			messagePacker.packString(KEY_RAILS).packArrayHeader(rails.size());
			for (final UUID rail : rails) {
				messagePacker.packString(rail.toString());
			}
		}

		@Override
		public int messagePackLength() {
			return super.messagePackLength() + 2;
		}

		@Override
		public void writePacket(FriendlyByteBuf packet) {
			super.writePacket(packet);
			packet.writeInt(color.ordinal());
			packet.writeInt(rails.size());
			rails.forEach(packet::writeUUID);
		}

		@Override
		protected boolean hasTransportMode() {
			return false;
		}

		public boolean isOccupied() {
			return occupied > 0;
		}

		/**
		 * @return its rails, for the dispatcher's console (D6)
		 */
		public Set<UUID> getRails() {
			return Collections.unmodifiableSet(rails);
		}

		private boolean isConnected(UUID checkRail) {
			final long checkPos1 = checkRail.getLeastSignificantBits();
			final long checkPos2 = checkRail.getMostSignificantBits();
			return rails.stream().anyMatch(rail -> {
				final long pos1 = rail.getLeastSignificantBits();
				final long pos2 = rail.getMostSignificantBits();
				return checkPos1 == pos1 || checkPos1 == pos2 || checkPos2 == pos1 || checkPos2 == pos2;
			});
		}

		@Override
		public int compareTo(NameColorDataBase compare) {
			return Long.compare(id, compare.id);
		}
	}
}
