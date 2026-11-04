package mtr.packet;

import mtr.data.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public abstract class PacketTrainDataBase implements IPacket {

	private static final int MAX_DATA_COUNT = 100000;
	/**
	 * Set when a change couldn't be made, so a group of changes made together can be undone (multiplayer edit conflicts).
	 */
	public static final ThreadLocal<Boolean> FAILED = ThreadLocal.withInitial(() -> false);

	protected static <T extends NameColorDataBase, U extends ReentrantBlockableEventLoop<? extends Runnable>> void updateData(Set<T> dataSet, Map<Long, T> cacheMap, U minecraft, FriendlyByteBuf packet, PacketCallback packetCallback, BiFunction<Long, TransportMode, T> createDataWithId, BiConsumer<T, List<String>> dataCallback) {
		final FriendlyByteBuf packetFullCopy = new FriendlyByteBuf(packet.copy());
		final long id = packet.readLong();
		final TransportMode transportMode = EnumHelper.valueOf(TransportMode.TRAIN, packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH));
		final String key = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		final FriendlyByteBuf packetCopy = new FriendlyByteBuf(packet.copy());
		minecraft.execute(() -> {
			try {
				final T data = cacheMap.get(id);
				if (data == null) {
					if (createDataWithId != null && dataSet.size() < MAX_DATA_COUNT) {
						final T newData = createDataWithId.apply(id, transportMode);
						dataSet.add(newData);
						newData.update(key, packetCopy);
						if (dataCallback != null) {
							dataCallback.accept(newData, new ArrayList<>());
						}
					}
				} else {
					final List<String> oldData = dataCallback == null ? new ArrayList<>() : RailwayDataLoggingModule.getData(data);
					data.update(key, packetCopy);
					if (dataCallback != null) {
						dataCallback.accept(data, oldData);
					}
				}
				packetCallback.packetCallback(packetCopy, packetFullCopy);
			} catch (Exception e) {
				e.printStackTrace();
				FAILED.set(true);
			}
		});
	}

	protected static <T extends NameColorDataBase, U extends ReentrantBlockableEventLoop<? extends Runnable>> void deleteData(Set<T> dataSet, Map<Long, T> cacheMap, U minecraft, FriendlyByteBuf packet, PacketCallback packetCallback, Consumer<T> dataCallback) {
		final FriendlyByteBuf packetFullCopy = new FriendlyByteBuf(packet.copy());
		final long id = packet.readLong();
		minecraft.execute(() -> {
			try {
				final T data = cacheMap.get(id);
				if (data != null) {
					if (dataCallback != null) {
						dataCallback.accept(data);
					}
					dataSet.remove(data);
				}
				packetCallback.packetCallback(null, packetFullCopy);
			} catch (Exception e) {
				e.printStackTrace();
				FAILED.set(true);
			}
		});
	}

	@FunctionalInterface
	protected interface PacketCallback {
		void packetCallback(FriendlyByteBuf updatePacket, FriendlyByteBuf fullPacket);
	}
}
