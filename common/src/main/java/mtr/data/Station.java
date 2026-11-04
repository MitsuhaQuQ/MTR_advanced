package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A station: an area on the map with a fare zone and exits. Platforms belong to the station whose area they are
 * in (see {@code DataCache.platformIdToStation}).
 */
public final class Station extends AreaBase {

	public int zone;
	public final Map<String, List<String>> exits = new HashMap<>();
	/**
	 * Rectangles as {x1, z1, x2, z2} in addition to the corners, which older data and other mods read.
	 */
	public final List<int[]> extraAreas = new ArrayList<>();

	public static final int MAX_EXTRA_AREAS = 15;

	private static final String KEY_ZONE = "zone";
	private static final String KEY_EXITS = "exits";
	private static final String KEY_EXTRA_AREAS = "extra_areas";

	private static final String KEY_EXIT_EDIT_PARENT = "exit_edit_parent";
	private static final String KEY_EXIT_DELETE_PARENT = "exit_delete_parent";
	private static final String KEY_EXIT_DESTINATIONS = "exit_destinations";

	public Station() {
		super();
	}

	public Station(long id) {
		super(id);
	}

	public Station(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		zone = messagePackHelper.getInt(KEY_ZONE);

		messagePackHelper.iterateMapValue(KEY_EXITS, entry -> {
			final List<String> destinations = new ArrayList<>(entry.getValue().asArrayValue().size());
			for (final Value destination : entry.getValue().asArrayValue()) {
				destinations.add(destination.asStringValue().asString());
			}
			exits.put(entry.getKey().asStringValue().asString(), destinations);
		});

		final List<Integer> values = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_EXTRA_AREAS, value -> values.add(value.asIntegerValue().asInt()));
		for (int i = 0; i + 3 < values.size() && extraAreas.size() < MAX_EXTRA_AREAS; i += 4) {
			extraAreas.add(new int[]{values.get(i), values.get(i + 1), values.get(i + 2), values.get(i + 3)});
		}
	}

	@Deprecated
	public Station(CompoundTag compoundTag) {
		super(compoundTag);
		zone = compoundTag.getInt(KEY_ZONE);

		final CompoundTag tagExits = compoundTag.getCompound(KEY_EXITS);
		for (final String keyParent : tagExits.getAllKeys()) {
			final List<String> destinations = new ArrayList<>();
			final CompoundTag tagDestinations = tagExits.getCompound(keyParent);
			for (final String keyDestination : tagDestinations.getAllKeys()) {
				destinations.add(tagDestinations.getString(keyDestination));
			}
			exits.put(keyParent, destinations);
		}
	}

	public Station(FriendlyByteBuf packet) {
		super(packet);
		zone = packet.readInt();
		final int exitCount = packet.readInt();
		for (int i = 0; i < exitCount; i++) {
			final String parent = packet.readUtf(PACKET_STRING_READ_LENGTH);
			final List<String> destinations = new ArrayList<>();
			final int destinationCount = packet.readInt();
			for (int j = 0; j < destinationCount; j++) {
				destinations.add(packet.readUtf(PACKET_STRING_READ_LENGTH));
			}
			exits.put(parent, destinations);
		}
		readExtraAreas(packet);
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_ZONE).packInt(zone);
		messagePacker.packString(KEY_EXITS);
		messagePacker.packMapHeader(exits.size());
		for (final Map.Entry<String, List<String>> entry : exits.entrySet()) {
			final String key = entry.getKey();
			final List<String> destinations = entry.getValue();
			messagePacker.packString(key);
			messagePacker.packArrayHeader(destinations.size());
			for (String destination : destinations) {
				messagePacker.packString(destination);
			}
		}

		messagePacker.packString(KEY_EXTRA_AREAS).packArrayHeader(extraAreas.size() * 4);
		for (final int[] extraArea : extraAreas) {
			for (final int value : extraArea) {
				messagePacker.packInt(value);
			}
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 3;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);
		packet.writeInt(zone);
		packet.writeInt(exits.size());
		exits.forEach((parent, destinations) -> {
			packet.writeUtf(parent);
			packet.writeInt(destinations.size());
			destinations.forEach(packet::writeUtf);
		});
		writeExtraAreas(packet);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_EXIT_EDIT_PARENT:
				final String oldParent = packet.readUtf(PACKET_STRING_READ_LENGTH);
				final String newParent = packet.readUtf(PACKET_STRING_READ_LENGTH);
				setExitParent(oldParent, newParent);
				break;
			case KEY_EXIT_DELETE_PARENT:
				exits.remove(packet.readUtf(PACKET_STRING_READ_LENGTH));
				break;
			case KEY_EXIT_DESTINATIONS:
				final String parent = packet.readUtf(PACKET_STRING_READ_LENGTH);
				if (parentExists(parent)) {
					exits.get(parent).clear();
					final int destinationCount = packet.readInt();
					for (int i = 0; i < destinationCount; i++) {
						exits.get(parent).add(packet.readUtf(PACKET_STRING_READ_LENGTH));
					}
				}
				break;
			case KEY_EXTRA_AREAS:
				readExtraAreas(packet);
				break;
			case KEY_ZONE:
				name = packet.readUtf(PACKET_STRING_READ_LENGTH);
				color = packet.readInt();
				zone = packet.readInt();
				break;
			default:
				super.update(key, packet);
				break;
		}
	}

	@Override
	protected boolean hasTransportMode() {
		return false;
	}

	@Override
	public boolean inArea(int x, int z) {
		if (super.inArea(x, z)) {
			return true;
		}
		for (final int[] extraArea : extraAreas) {
			if (RailwayData.isBetween(x, extraArea[0], extraArea[2]) && RailwayData.isBetween(z, extraArea[1], extraArea[3])) {
				return true;
			}
		}
		return false;
	}

	@Override
	public List<int[]> getRectangles() {
		final List<int[]> rectangles = super.getRectangles();
		rectangles.addAll(extraAreas);
		return rectangles;
	}

	public void setExtraAreas(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_EXTRA_AREAS);
		writeExtraAreas(packet);
		sendPacket.accept(packet);
	}

	private void readExtraAreas(FriendlyByteBuf packet) {
		extraAreas.clear();
		final int count = packet.readInt();
		for (int i = 0; i < count; i++) {
			final int[] extraArea = {packet.readInt(), packet.readInt(), packet.readInt(), packet.readInt()};
			if (i < MAX_EXTRA_AREAS) {
				extraAreas.add(extraArea);
			}
		}
	}

	private void writeExtraAreas(FriendlyByteBuf packet) {
		packet.writeInt(extraAreas.size());
		extraAreas.forEach(extraArea -> {
			for (final int value : extraArea) {
				packet.writeInt(value);
			}
		});
	}

	public void setZone(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_ZONE);
		packet.writeUtf(name);
		packet.writeInt(color);
		packet.writeInt(zone);
		sendPacket.accept(packet);
	}

	public void setExitParent(String oldParent, String newParent, Consumer<FriendlyByteBuf> sendPacket) {
		setExitParent(oldParent, newParent);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_EXIT_EDIT_PARENT);
		packet.writeUtf(oldParent);
		packet.writeUtf(newParent);
		sendPacket.accept(packet);
	}

	public void deleteExitParent(String parent, Consumer<FriendlyByteBuf> sendPacket) {
		exits.remove(parent);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_EXIT_DELETE_PARENT);
		packet.writeUtf(parent);
		sendPacket.accept(packet);
	}

	public void setExitDestinations(String parent, Consumer<FriendlyByteBuf> sendPacket) {
		if (parentExists(parent)) {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeLong(id);
			packet.writeUtf(transportMode.toString());
			packet.writeUtf(KEY_EXIT_DESTINATIONS);
			packet.writeUtf(parent);
			packet.writeInt(exits.get(parent).size());
			exits.get(parent).forEach(packet::writeUtf);
			sendPacket.accept(packet);
		}
	}

	public Map<String, List<String>> getGeneratedExits() {
		final List<String> exitParents = new ArrayList<>(exits.keySet());
		exitParents.sort(String::compareTo);

		final Map<String, List<String>> generatedExits = new HashMap<>();
		exitParents.forEach(parent -> {
			final String exitLetter = parent.substring(0, 1);
			if (!generatedExits.containsKey(exitLetter)) {
				generatedExits.put(exitLetter, new ArrayList<>());
			}

			generatedExits.get(exitLetter).addAll(exits.get(parent));
			generatedExits.put(parent, exits.get(parent));
		});

		return generatedExits;
	}

	private void setExitParent(String oldParent, String newParent) {
		if (parentExists(oldParent)) {
			final List<String> existingDestinations = exits.get(oldParent);
			exits.remove(oldParent);
			exits.put(newParent, existingDestinations == null ? new ArrayList<>() : existingDestinations);
		} else {
			exits.put(newParent, new ArrayList<>());
		}
	}

	private boolean parentExists(String parent) {
		return parent != null && exits.containsKey(parent);
	}

	public static long serializeExit(String exit) {
		final char[] characters = exit.toCharArray();
		long code = 0;
		for (final char character : characters) {
			code = code << 8;
			code += character;
		}
		return code;
	}

	public static String deserializeExit(long code) {
		StringBuilder exit = new StringBuilder();
		long charCodes = code;
		while (charCodes > 0) {
			exit.insert(0, (char) (charCodes & 0xFF));
			charCodes = charCodes >> 8;
		}
		return exit.toString();
	}
}
