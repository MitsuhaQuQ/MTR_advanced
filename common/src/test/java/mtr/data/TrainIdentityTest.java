package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A train going on as a train of another siding of its pool is the same train (R1): a new train of that siding holding the
 * same identity, and the pool's vehicle settings, set once in the depot, kept in saves, packets and updates.
 */
class TrainIdentityTest {

	@BeforeAll
	static void bootstrap() {
		// A train has its cargo, which needs the game's registries
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	private static TrainServer train(long id, long sidingId) {
		return new TrainServer(id, sidingId, 40, "train", "train_24_2", 2, new ArrayList<>(), new ArrayList<>(), 0, 0, Train.ACCELERATION_DEFAULT, new ArrayList<>(), false, 0, 0);
	}

	@Test
	void aSavedTrainIsAsLongAsItSaysWithWhatItIsToldToPass() throws IOException {
		// A train saved one value short leaves its last one to be read as the next train of its siding (D3 found it)
		final TrainServer train = train(5, 100);
		train.getService().setSkipPlatforms(new TreeSet<>(Arrays.asList(3L, 4L)));
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(train.messagePackLength());
		train.toMessagePack(packer);
		packer.packString("next");
		packer.close();
		final org.msgpack.core.MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue());
		assertEquals("next", unpacker.unpackString());
		assertTrue(map.containsKey("cruise_cap"));
		final TrainServer read = new TrainServer(100, 40, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0, 0, Train.ACCELERATION_DEFAULT, false, 0, 0, map);
		assertEquals(new TreeSet<>(Arrays.asList(3L, 4L)), read.getService().getSkipPlatforms());
	}

	@Test
	void theSameTrainOfAnotherSidingHoldsItsIdentity() {
		final TrainServer train = train(77, 100);
		final UUID rider = UUID.randomUUID();
		train.ridingEntities.add(rider);
		final List<String> kept = train.identity.getAttachment("test", ArrayList::new);
		kept.add("settings");

		final TrainServer same = new TrainServer(train, 200, 40, "train", "train_24_2", 2, new ArrayList<>(), new ArrayList<>(), 0, 0, Train.ACCELERATION_DEFAULT, new ArrayList<>(), false, 0, 0);
		assertEquals(77, same.id);
		assertEquals(200, same.sidingId);
		assertSame(train.identity, same.identity);
		assertSame(train.inventory, same.inventory);
		assertTrue(same.ridingEntities.contains(rider));
		assertEquals(Collections.singletonList("settings"), same.identity.getAttachment("test", ArrayList::new));
		// Its run state is its own
		assertNotSame(train, same);
		assertFalse(same.getIsOnRoute());
	}

	@Test
	void aTrainRunsAsItsPoolsVehicleNow() {
		final TrainServer train = train(1, 100);
		train.setVehicle(0.02F, true, 3, 10);
		assertEquals(0.02F, train.accelerationConstant);
		assertTrue(train.isManualAllowed);
		assertEquals(3, train.maxManualSpeed);
		assertEquals(10, train.manualToAutomaticTime);
	}

	@Test
	void aPoolLeftOutRunsAsItsFirstSiding() {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		final Siding first = new Siding(5, TransportMode.TRAIN, new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), 40);
		final Siding second = new Siding(9, TransportMode.TRAIN, new BlockPos(0, 64, 10), new BlockPos(40, 64, 10), 40);
		first.setUnlimitedTrains(false, 1, true, 2, 0.02F, 0, false, packet -> {
		});
		depot.addToPool(second);
		depot.addToPool(first);
		assertEquals(first.getOwnVehicleSettings(), depot.getVehicleSettings(second));
		final VehicleSettings set = new VehicleSettings(0.03F, 0.04F, false, 0, 0);
		depot.vehicleSettings.put(second.getPoolKey(), set);
		assertEquals(set, depot.getVehicleSettings(second));
	}

	@Test
	void vehicleSettingsAreSavedSentAndUpdated() throws IOException {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		final Map<String, VehicleSettings> settings = new TreeMap<>();
		settings.put("train/2", new VehicleSettings(0.03F, 0, true, 4, 20, 90));
		depot.setVehicleSettings(settings, sent::add);

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(depot.messagePackLength());
		depot.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		assertEquals(settings, new Depot(map).vehicleSettings);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		depot.writePacket(packet);
		assertEquals(settings, new Depot(packet).vehicleSettings);

		final Depot updated = new Depot(1, TransportMode.TRAIN);
		final FriendlyByteBuf update = sent.get(0);
		update.readLong();
		update.readUtf();
		updated.update(update.readUtf(), update);
		assertEquals(settings, updated.vehicleSettings);
	}
}
