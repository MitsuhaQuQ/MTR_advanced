package mtr.advanced;

import mtr.data.MessagePackHelper;
import mtr.data.SerializedDataBase;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import transitsim.network.Boundaries;
import transitsim.network.RailKeys;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Where players cut the rail network for pathfinding (design document 5.13), with nodes as
 * {@link net.minecraft.core.BlockPos#asLong()}: the boundaries of the simulation component, saved and sent to players.
 * The dashboard and editor use the regions it makes. This is separate from the timetable boundaries of platforms.
 */
public final class PathBoundaries extends Boundaries {

	public static final int MAX_BOUNDARIES = 65536;

	public static UUID segmentKey(long pos1, long pos2) {
		return RailKeys.of(pos1, pos2);
	}

	@Override
	public PathBoundaries copy() {
		final PathBoundaries copy = new PathBoundaries();
		copy.nodes.addAll(nodes);
		copy.segments.addAll(segments);
		return copy;
	}

	public void write(FriendlyByteBuf packet) {
		write(this, packet);
	}

	public static void write(Boundaries boundaries, FriendlyByteBuf packet) {
		packet.writeInt(boundaries.nodes.size());
		boundaries.nodes.forEach(packet::writeLong);
		packet.writeInt(boundaries.segments.size());
		boundaries.segments.forEach(packet::writeUUID);
	}

	public static PathBoundaries read(FriendlyByteBuf packet) {
		final PathBoundaries boundaries = new PathBoundaries();
		final int nodeCount = Math.min(packet.readInt(), MAX_BOUNDARIES);
		for (int i = 0; i < nodeCount; i++) {
			boundaries.nodes.add(packet.readLong());
		}
		final int segmentCount = Math.min(packet.readInt(), MAX_BOUNDARIES);
		for (int i = 0; i < segmentCount; i++) {
			boundaries.segments.add(packet.readUUID());
		}
		return boundaries;
	}

	/**
	 * One boundary in the save, either a node or a rail.
	 */
	public static final class Entry extends SerializedDataBase {

		public final long node;
		public final UUID segment;

		private static final String KEY_POS = "pos";
		private static final String KEY_POS_1 = "pos_1";
		private static final String KEY_POS_2 = "pos_2";

		public Entry(long node) {
			this.node = node;
			segment = null;
		}

		public Entry(UUID segment) {
			node = 0;
			this.segment = segment;
		}

		public Entry(Map<String, Value> map) {
			final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
			if (map.containsKey(KEY_POS_1)) {
				node = 0;
				segment = segmentKey(messagePackHelper.getLong(KEY_POS_1), messagePackHelper.getLong(KEY_POS_2));
			} else {
				node = messagePackHelper.getLong(KEY_POS);
				segment = null;
			}
		}

		/**
		 * @return where this entry is saved, which only has to be unique among entries of the same kind
		 */
		public long getId() {
			return segment == null ? node : segment.getMostSignificantBits() * 31 + segment.getLeastSignificantBits();
		}

		@Override
		public void toMessagePack(MessagePacker messagePacker) throws IOException {
			if (segment == null) {
				messagePacker.packString(KEY_POS).packLong(node);
			} else {
				messagePacker.packString(KEY_POS_1).packLong(segment.getMostSignificantBits());
				messagePacker.packString(KEY_POS_2).packLong(segment.getLeastSignificantBits());
			}
		}

		@Override
		public int messagePackLength() {
			return segment == null ? 1 : 2;
		}

		@Override
		public void writePacket(FriendlyByteBuf packet) {
		}
	}
}
