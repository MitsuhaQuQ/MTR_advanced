package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A duty's path of a whole day is sent to players a round at a time, the round the train is on and the next, and the next
 * part only as the train turns back (2026-11-09): sent whole it was bigger than a packet may be, and players never saw
 * the train.
 */
class TrainSendWindowTest {

	private static final int STOPS = 6;
	private static final int ROUNDS = 400;
	private static final int LENGTH = 20;

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void aWholeDaysDutyIsSentByRoundsAndTheNextAtTheTurnBack() {
		// Out and back on a line of six pieces, each ending at a stop, four hundred times
		final List<PathData> path = new ArrayList<>();
		final List<Double> distances = new ArrayList<>();
		final Rail[] forwards = new Rail[STOPS];
		final Rail[] backwards = new Rail[STOPS];
		for (int i = 0; i < STOPS; i++) {
			forwards[i] = new Rail(pos(i), RailAngle.E, pos(i + 1), RailAngle.W, RailType.IRON, TransportMode.TRAIN);
			backwards[i] = new Rail(pos(i + 1), RailAngle.W, pos(i), RailAngle.E, RailType.IRON, TransportMode.TRAIN);
		}
		double distance = 0;
		for (int round = 0; round < ROUNDS; round++) {
			for (int i = 0; i < STOPS; i++) {
				final int piece = round % 2 == 0 ? i : STOPS - 1 - i;
				path.add(round % 2 == 0 ? new PathData(forwards[piece], 0, 20, pos(piece), pos(piece + 1), i + 1) : new PathData(backwards[piece], 0, 20, pos(piece + 1), pos(piece), i + 1));
				distance += LENGTH;
				distances.add(distance);
			}
		}
		final TrainServer train = new TrainServer(1, 2, 50, "train", "train_24_2", 2, path, distances, 0, 0, Train.ACCELERATION_DEFAULT, new ArrayList<>(), false, 0, 0);
		assertTrue(path.size() > Train.SEND_WINDOW_MIN);

		// Partway through round 100: that round and the next are sent, from a little before for the train's back cars
		final int round = 100;
		train.railProgress = (round * STOPS + 2) * LENGTH + 5;
		train.nextStoppingIndex = round * STOPS + 2;
		train.nextPlatformIndex = train.nextStoppingIndex;
		final Received received = send(train);
		assertTrue(received.pathSize() <= 3 * STOPS, received.pathSize() + " pieces");
		assertEquals(train.getSyncKey(), received.getSyncKey(), "the client works out the same key for its part");
		assertSamePlace(train, received);

		// Further on in the same round, the same part: only what changes is sent
		final int key = train.getSyncKey();
		train.railProgress = (round * STOPS + 5) * LENGTH + 5;
		assertEquals(key, train.getSyncKey());
		assertSamePlace(train, send(train));

		// Standing at the end of the round, where it turns back, the next part
		train.railProgress = (round + 1) * STOPS * LENGTH;
		final int turnedKey = train.getSyncKey();
		assertNotEquals(key, turnedKey);
		final Received turned = send(train);
		assertEquals(turnedKey, turned.getSyncKey());
		assertSamePlace(train, turned);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		train.writePacket(packet);
		assertTrue(packet.readableBytes() < 10000, packet.readableBytes() + " bytes");
	}

	@Test
	void aShortPathIsSentWhole() {
		final List<PathData> path = new ArrayList<>();
		final List<Double> distances = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			path.add(new PathData(new Rail(pos(i), RailAngle.E, pos(i + 1), RailAngle.W, RailType.IRON, TransportMode.TRAIN), 0, 20, pos(i), pos(i + 1), i + 1));
			distances.add((i + 1.0) * LENGTH);
		}
		final TrainServer train = new TrainServer(1, 2, 50, "train", "train_24_2", 2, path, distances, 0, 0, Train.ACCELERATION_DEFAULT, new ArrayList<>(), false, 0, 0);
		train.railProgress = 105;
		final Received received = send(train);
		assertEquals(10, received.pathSize());
		assertEquals(105, received.railProgress, 1E-9);
		assertEquals(train.getSyncKey(), received.getSyncKey());
	}

	@Test
	void theStateCarriesTheKeyAsAddedToByMixins() {
		// ANTE's mixin adds its settings to getSyncKey(); the client compares a state with that, so a state written with a key
		// worked out apart from it was dropped every time, and trains never opened their doors (2026-11-09)
		final List<PathData> path = new ArrayList<>();
		final List<Double> distances = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			path.add(new PathData(new Rail(pos(i), RailAngle.E, pos(i + 1), RailAngle.W, RailType.IRON, TransportMode.TRAIN), 0, 20, pos(i), pos(i + 1), i + 1));
			distances.add((i + 1.0) * LENGTH);
		}
		final TrainServer train = new TrainServer(1, 2, 50, "train", "train_24_2", 2, path, distances, 0, 0, Train.ACCELERATION_DEFAULT, new ArrayList<>(), false, 0, 0) {
			@Override
			public int getSyncKey() {
				return super.getSyncKey() * 31 + 12345;
			}
		};
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		train.writeStatePacket(packet);
		assertEquals(1, packet.readLong());
		assertEquals(train.getSyncKey(), packet.readInt());
	}

	private static void assertSamePlace(TrainServer train, Received received) {
		final int index = train.getIndex(train.railProgress, false);
		final int receivedIndex = received.getIndex(received.railProgress, false);
		final PathData piece = train.path.get(index);
		final PathData receivedPiece = received.path.get(receivedIndex);
		assertEquals(piece.startingPos, receivedPiece.startingPos);
		assertEquals(piece.getLastPos(), receivedPiece.getLastPos());
		assertEquals(piece.stopIndex, receivedPiece.stopIndex);
		final double into = train.railProgress - (index == 0 ? 0 : train.distances.get(index - 1));
		final double receivedInto = received.railProgress - (receivedIndex == 0 ? 0 : received.distances.get(receivedIndex - 1));
		assertEquals(into, receivedInto, 1E-9);
		assertEquals(train.path.get(train.nextStoppingIndex).getLastPos(), received.path.get(received.nextStoppingIndex).getLastPos());
		// The back car's place is there too
		assertTrue(received.railProgress - 50 >= 0);
	}

	private static Received send(TrainServer train) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		train.writePacket(packet);
		final Received received = new Received(packet);
		assertEquals(0, packet.readableBytes());
		return received;
	}

	private static BlockPos pos(int i) {
		return new BlockPos(i * LENGTH, 64, 0);
	}

	/**
	 * The train as a player gets it.
	 */
	private static final class Received extends Train {

		private Received(FriendlyByteBuf packet) {
			super(packet);
		}

		private int pathSize() {
			return path.size();
		}

		@Override
		protected void simulateCar(Level world, int ridingCar, float ticksElapsed, double carX, double carY, double carZ, float carYaw, float carPitch, double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch, boolean doorLeftOpen, boolean doorRightOpen, double realSpacing) {
		}

		@Override
		protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed) {
			return false;
		}

		@Override
		protected boolean canDeploy(Depot depot) {
			return false;
		}

		@Override
		protected boolean isRailBlocked(int checkIndex) {
			return false;
		}

		@Override
		protected boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ) {
			return true;
		}

		@Override
		protected boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks) {
			return false;
		}

		@Override
		protected double asin(double value) {
			return Math.asin(value);
		}
	}
}
