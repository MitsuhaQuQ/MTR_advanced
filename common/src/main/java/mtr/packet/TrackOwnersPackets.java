package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.data.RailwayData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import transitsim.network.RailKeys;
import transitsim.network.TrackSections;

import java.util.*;

/**
 * The owners of the track sections as the server worked them out (web layers step 2, 2026-11-09), for the advanced
 * editor's whole network map to show: each section with owners, or with owners set by hand, as its rails, its owners and
 * whether they were set by hand. A section not sent can be run by every line. Sent to every player when the owners are
 * worked out again, and to a player asking for the map; in parts, as a large network doesn't fit in one packet.
 */
public final class TrackOwnersPackets implements IPacket {

	/**
	 * The owners as the client has them, by rail key: the indices of its owners in {@link #ownerNames}, and whether they
	 * were set by hand. Read on the editor's threads, so replaced whole once all parts have come.
	 */
	public static volatile Map<UUID, Owners> railOwners = Collections.emptyMap();
	public static volatile List<String> ownerNames = Collections.emptyList();
	/**
	 * Goes up with each set of owners received in full.
	 */
	public static volatile int version;

	private static final int PART_BYTES = 768 * 1024;
	private static final int MAX_NAMES = 65536;
	private static final int MAX_SECTIONS = 1 << 20;
	private static final int MAX_RAILS = 1 << 16;
	private static final int MAX_NAME_LENGTH = 256;

	private static final Map<UUID, Owners> RECEIVING = new HashMap<>();
	private static final List<String> RECEIVING_NAMES = new ArrayList<>();
	private static int receivingVersion = -1;

	private TrackOwnersPackets() {
	}

	public static final class Owners {

		public final int[] owners;
		public final boolean manual;
		/**
		 * The section's logical line, or 0.
		 */
		public final long logicalLine;

		private Owners(int[] owners, boolean manual, long logicalLine) {
			this.owners = owners;
			this.manual = manual;
			this.logicalLine = logicalLine;
		}
	}

	/**
	 * Sends the owners to every player of the world if they were worked out again since they were last sent.
	 */
	public static void sendAllIfChanged(Level world, RailwayData railwayData) {
		final int ownersVersion = railwayData.routePaths.getOwnersVersion();
		if (ownersVersion == railwayData.ownersSentVersion) {
			return;
		}
		railwayData.ownersSentVersion = ownersVersion;
		final List<FriendlyByteBuf> packets = write(railwayData);
		if (packets != null) {
			world.players().forEach(player -> packets.forEach(packet -> Registry.sendToPlayer((ServerPlayer) player, PACKET_TRACK_OWNERS, new FriendlyByteBuf(packet.copy()))));
		}
	}

	public static void send(ServerPlayer player, RailwayData railwayData) {
		final List<FriendlyByteBuf> packets = write(railwayData);
		if (packets != null) {
			packets.forEach(packet -> Registry.sendToPlayer(player, PACKET_TRACK_OWNERS, packet));
		}
	}

	/**
	 * @return the packets, or null if the owners haven't been worked out yet
	 */
	private static List<FriendlyByteBuf> write(RailwayData railwayData) {
		final mtr.advanced.GraphRecords.Legs legs = railwayData.graphRecords.getLegs();
		if (legs == null || !railwayData.routePaths.isOwnersReady()) {
			return null;
		}
		return write(legs.snapshot, legs.owners, railwayData.routePaths.getManualSections(), railwayData.routePaths.getSectionLogicalLines(), railwayData.routePaths.getOwnersVersion(), PART_BYTES);
	}

	/**
	 * @param partBytes about how many bytes of sections a packet holds before the next part starts
	 */
	static List<FriendlyByteBuf> write(TrackSections.Snapshot snapshot, Map<Long, Set<String>> sectionOwners, Set<Long> manual, Map<Long, Long> logical, int sendVersion, int partBytes) {
		final Set<Long> hashes = new TreeSet<>(sectionOwners.keySet());
		hashes.addAll(manual);
		hashes.addAll(logical.keySet());
		final Map<String, Integer> nameIndices = new LinkedHashMap<>();
		sectionOwners.values().forEach(owners -> owners.forEach(owner -> nameIndices.putIfAbsent(owner, nameIndices.size())));

		final List<FriendlyByteBuf> packets = new ArrayList<>();
		FriendlyByteBuf part = null;
		int count = 0;
		FriendlyByteBuf sections = new FriendlyByteBuf(Unpooled.buffer());
		for (final long hash : hashes) {
			final TrackSections.Section section = snapshot.sections.get(hash);
			if (section == null) {
				continue;
			}
			final Set<String> owners = sectionOwners.getOrDefault(hash, Collections.emptySet());
			sections.writeVarInt(owners.size());
			for (final String owner : owners) {
				sections.writeVarInt(nameIndices.get(owner));
			}
			sections.writeBoolean(manual.contains(hash));
			sections.writeLong(logical.getOrDefault(hash, 0L));
			final int railCount = Math.min(section.getRailCount(), MAX_RAILS);
			sections.writeVarInt(railCount);
			for (int i = 0; i < railCount; i++) {
				final TrackSections.Rail rail = section.getRail(i);
				sections.writeLong(rail.node1);
				sections.writeLong(rail.node2);
			}
			count++;
			if (sections.readableBytes() > partBytes) {
				packets.add(part(sendVersion, packets.isEmpty(), false, nameIndices, count, sections));
				sections = new FriendlyByteBuf(Unpooled.buffer());
				count = 0;
			}
		}
		packets.add(part(sendVersion, packets.isEmpty(), true, nameIndices, count, sections));
		return packets;
	}

	private static FriendlyByteBuf part(int sendVersion, boolean first, boolean last, Map<String, Integer> nameIndices, int count, FriendlyByteBuf sections) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(sendVersion);
		packet.writeBoolean(first);
		packet.writeBoolean(last);
		if (first) {
			packet.writeVarInt(nameIndices.size());
			nameIndices.keySet().forEach(name -> packet.writeUtf(name, MAX_NAME_LENGTH));
		}
		packet.writeVarInt(count);
		packet.writeBytes(sections);
		return packet;
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final int sendVersion = packet.readInt();
		final boolean first = packet.readBoolean();
		final boolean last = packet.readBoolean();
		synchronized (RECEIVING) {
			if (first) {
				RECEIVING.clear();
				RECEIVING_NAMES.clear();
				receivingVersion = sendVersion;
				final int nameCount = packet.readVarInt();
				if (nameCount < 0 || nameCount > MAX_NAMES) {
					receivingVersion = -1;
					return;
				}
				for (int i = 0; i < nameCount; i++) {
					RECEIVING_NAMES.add(packet.readUtf(MAX_NAME_LENGTH));
				}
			} else if (sendVersion != receivingVersion) {
				// A part of a set whose first part was missed, or of an older set
				return;
			}
			final int count = packet.readVarInt();
			if (count < 0 || count > MAX_SECTIONS) {
				receivingVersion = -1;
				return;
			}
			for (int i = 0; i < count; i++) {
				final int ownerCount = packet.readVarInt();
				if (ownerCount < 0 || ownerCount > RECEIVING_NAMES.size()) {
					receivingVersion = -1;
					return;
				}
				final int[] owners = new int[ownerCount];
				for (int j = 0; j < ownerCount; j++) {
					owners[j] = packet.readVarInt();
					if (owners[j] < 0 || owners[j] >= RECEIVING_NAMES.size()) {
						receivingVersion = -1;
						return;
					}
				}
				final boolean manual = packet.readBoolean();
				final Owners entry = new Owners(owners, manual, packet.readLong());
				final int railCount = packet.readVarInt();
				if (railCount < 0 || railCount > MAX_RAILS) {
					receivingVersion = -1;
					return;
				}
				for (int j = 0; j < railCount; j++) {
					RECEIVING.put(RailKeys.of(packet.readLong(), packet.readLong()), entry);
				}
			}
			if (last && receivingVersion == sendVersion) {
				railOwners = Collections.unmodifiableMap(new HashMap<>(RECEIVING));
				ownerNames = Collections.unmodifiableList(new ArrayList<>(RECEIVING_NAMES));
				version++;
				RECEIVING.clear();
				receivingVersion = -1;
			}
		}
	}
}
