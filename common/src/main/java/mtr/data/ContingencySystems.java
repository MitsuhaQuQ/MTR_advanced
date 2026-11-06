package mtr.data;

import mtr.advanced.PacketBinary;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import transitsim.network.PlatformGraph;
import transitsim.plan.Contingencies;

import java.io.IOException;
import java.util.*;

/**
 * The contingency plans of each rail system (D2): a set of plans, and their state, for every route of one network of
 * rails joined together, whichever depot's trains run them, as the user asked (the network, not the depot). A system
 * is known by a platform of its network, its anchor, as networks are worked out again whenever the rails change; a
 * route belongs to the systems whose anchors are in the same network as its first platform.
 * <p>
 * Rails laid between two systems join them, and nothing of either is lost: both stay saved, both hold, the lower anchor
 * first, and the web editor shows the network made of both until a player merges them into one. A system whose anchor
 * platform is removed moves to another platform of its network it noted while it could ({@link #backups}).
 */
public final class ContingencySystems {

	public static final int MAX_SYSTEMS = 64;

	/**
	 * Anchor platform → its plans and their state.
	 */
	public final Map<Long, Contingencies> systems = new TreeMap<>();
	/**
	 * Anchor → other platforms of its network, the lowest first, which it moves to if its own is removed.
	 */
	public final Map<Long, List<Long>> backups = new TreeMap<>();
	private static final int MAX_BACKUPS = 8;

	/**
	 * @return the anchors of the systems the route belongs to, the lowest first: more than one where rails joined them
	 */
	public List<Long> anchorsForRoute(Route route, PlatformGraph platformGraph) {
		if (route == null || route.platformIds.isEmpty() || systems.isEmpty()) {
			return Collections.emptyList();
		}
		return anchorsOf(platformGraph.getNetworkOf(route.getFirstPlatformId()), platformGraph);
	}

	/**
	 * @return the anchors of the systems of a network, the lowest first
	 */
	public List<Long> anchorsOf(long network, PlatformGraph platformGraph) {
		final List<Long> anchors = new ArrayList<>();
		if (network != 0) {
			systems.keySet().forEach(anchor -> {
				if (platformGraph.getNetworkOf(anchor) == network) {
					anchors.add(anchor);
				}
			});
		}
		return anchors;
	}

	/**
	 * @return the plans of the systems the route belongs to, the lowest anchor first
	 */
	public List<Contingencies> forRoute(Route route, PlatformGraph platformGraph) {
		final List<Contingencies> list = new ArrayList<>();
		anchorsForRoute(route, platformGraph).forEach(anchor -> list.add(systems.get(anchor)));
		return list;
	}

	/**
	 * Notes other platforms of each system's network while its anchor is there, and moves a system whose anchor platform
	 * was removed to the first of them still there that isn't an anchor itself.
	 *
	 * @return the systems moved, as old anchor → new anchor
	 */
	public Map<Long, Long> keepAnchors(PlatformGraph platformGraph, java.util.function.LongPredicate platformExists) {
		final Map<Long, Long> moved = new TreeMap<>();
		for (final long anchor : new ArrayList<>(systems.keySet())) {
			if (platformExists.test(anchor)) {
				final long network = platformGraph.getNetworkOf(anchor);
				if (network != 0) {
					final List<Long> others = new ArrayList<>();
					for (int i = 0; i < platformGraph.size(); i++) {
						final long platformId = platformGraph.getPlatformId(i);
						if (platformGraph.getNetwork(i) == network && platformId != anchor) {
							others.add(platformId);
						}
					}
					Collections.sort(others);
					backups.put(anchor, new ArrayList<>(others.subList(0, Math.min(MAX_BACKUPS, others.size()))));
				}
				continue;
			}
			for (final long candidate : backups.getOrDefault(anchor, Collections.emptyList())) {
				if (platformExists.test(candidate) && !systems.containsKey(candidate)) {
					systems.put(candidate, systems.remove(anchor));
					final List<Long> rest = new ArrayList<>(backups.remove(anchor));
					rest.remove(candidate);
					backups.put(candidate, rest);
					moved.put(anchor, candidate);
					break;
				}
			}
		}
		backups.keySet().retainAll(systems.keySet());
		return moved;
	}

	/**
	 * @return the anchor of the system of a network: an existing one in it, or else the lowest platform of the network
	 */
	public long anchorOf(long network, PlatformGraph platformGraph) {
		for (final long anchor : systems.keySet()) {
			if (platformGraph.getNetworkOf(anchor) == network) {
				return anchor;
			}
		}
		long lowest = 0;
		for (int i = 0; i < platformGraph.size(); i++) {
			if (platformGraph.getNetwork(i) == network && (lowest == 0 || platformGraph.getPlatformId(i) < lowest)) {
				lowest = platformGraph.getPlatformId(i);
			}
		}
		return lowest;
	}

	public Contingencies getOrCreate(long anchor) {
		return systems.computeIfAbsent(anchor, key -> new Contingencies());
	}

	public void write(FriendlyByteBuf packet) {
		packet.writeVarInt(systems.size());
		systems.forEach((anchor, contingencies) -> {
			packet.writeLong(anchor);
			final List<Long> anchorBackups = backups.getOrDefault(anchor, Collections.emptyList());
			packet.writeVarInt(anchorBackups.size());
			anchorBackups.forEach(packet::writeLong);
			contingencies.writePlans(PacketBinary.of(packet));
			contingencies.writeState(PacketBinary.of(packet));
		});
	}

	public void read(FriendlyByteBuf packet) {
		final int count = packet.readVarInt();
		if (count < 0 || count > MAX_SYSTEMS) {
			throw new IllegalArgumentException("Too many systems: " + count);
		}
		final Map<Long, Contingencies> read = new TreeMap<>();
		final Map<Long, List<Long>> readBackups = new TreeMap<>();
		for (int i = 0; i < count; i++) {
			final long anchor = packet.readLong();
			final int backupCount = packet.readVarInt();
			if (backupCount < 0 || backupCount > MAX_BACKUPS) {
				throw new IllegalArgumentException("Too many backups: " + backupCount);
			}
			final List<Long> anchorBackups = new ArrayList<>();
			for (int j = 0; j < backupCount; j++) {
				anchorBackups.add(packet.readLong());
			}
			readBackups.put(anchor, anchorBackups);
			final Contingencies contingencies = new Contingencies();
			contingencies.readPlans(PacketBinary.of(packet));
			contingencies.readState(PacketBinary.of(packet));
			read.put(anchor, contingencies);
		}
		systems.clear();
		systems.putAll(read);
		backups.clear();
		backups.putAll(readBackups);
	}

	/**
	 * One system in the save.
	 */
	public static final class Entry extends SerializedDataBase {

		public final long anchor;
		public final byte[] bytes;
		/**
		 * Other platforms of its network, which it moves to if its anchor is removed.
		 */
		public final List<Long> backups = new ArrayList<>();

		private static final String KEY_ANCHOR = "anchor";
		private static final String KEY_CONTINGENCIES = "contingencies";
		private static final String KEY_BACKUPS = "backups";

		public Entry(long anchor, Contingencies contingencies, List<Long> backups) {
			this.anchor = anchor;
			bytes = contingencies.toBytes();
			if (backups != null) {
				this.backups.addAll(backups);
			}
		}

		public Entry(Map<String, Value> map) {
			final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
			anchor = messagePackHelper.getLong(KEY_ANCHOR);
			final Value value = map.get(KEY_CONTINGENCIES);
			bytes = value != null && value.isBinaryValue() ? value.asBinaryValue().asByteArray() : new byte[0];
			messagePackHelper.iterateArrayValue(KEY_BACKUPS, backup -> {
				if (backups.size() < MAX_BACKUPS) {
					backups.add(backup.asIntegerValue().asLong());
				}
			});
		}

		public Contingencies toContingencies() {
			final Contingencies contingencies = new Contingencies();
			contingencies.fromBytes(bytes);
			return contingencies;
		}

		@Override
		public void toMessagePack(MessagePacker messagePacker) throws IOException {
			messagePacker.packString(KEY_ANCHOR).packLong(anchor);
			messagePacker.packString(KEY_CONTINGENCIES).packBinaryHeader(bytes.length);
			messagePacker.writePayload(bytes);
			messagePacker.packString(KEY_BACKUPS).packArrayHeader(backups.size());
			for (final long backup : backups) {
				messagePacker.packLong(backup);
			}
		}

		@Override
		public int messagePackLength() {
			return 3;
		}

		@Override
		public void writePacket(FriendlyByteBuf packet) {
		}
	}
}
