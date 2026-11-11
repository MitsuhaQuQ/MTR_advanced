package transitsim.run;

import org.junit.jupiter.api.Test;
import transitsim.sim.RunReservations;
import transitsim.sim.SimDataset;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Movement authority through one arbiter (run layer, 5.5): a train behind another is let on section by section up to it,
 * a train turning back at its platform keeps the one behind out, and a single track is never given both ways.
 */
class AuthorityTest {

	private static SimDataset.DataTrack plain(long from, long to, double length, boolean twoWay) {
		return new SimDataset.DataTrack(from, to, length, false, false, 0, 0, 0, 1, true, true, twoWay);
	}

	private static SimDataset.DataTrack platform(long from, long to, boolean twoWay) {
		return new SimDataset.DataTrack(from, to, 30, true, false, 1, 20, 0, 1, false, true, twoWay);
	}

	private static PathClaims.Path<SimDataset.DataTrack> path(SimDataset.DataTrack... tracks) {
		final List<SimDataset.DataTrack> list = Arrays.asList(tracks);
		final double[] ends = new double[tracks.length];
		double sum = 0;
		for (int i = 0; i < tracks.length; i++) {
			sum += tracks[i].getTrackLength();
			ends[i] = sum;
		}
		return new PathClaims.Path<SimDataset.DataTrack>() {
			@Override
			public List<SimDataset.DataTrack> getTracks() {
				return list;
			}

			@Override
			public double getEnd(int track) {
				return ends[track];
			}

			@Override
			public boolean turnsBack(int track, int nextTrack) {
				return list.get(track).isOppositeOf(list.get(nextTrack));
			}
		};
	}

	/**
	 * One tick: each train holds and asks, then the arbiter decides.
	 */
	private static double[] tick(RunReservations reservations, Runnable... trains) {
		for (final Runnable train : trains) {
			train.run();
		}
		reservations.resolve(0);
		return null;
	}

	@Test
	void aTrainWaitingAtItsPlatformReleasesReservationsBeyondItsBody() {
		final PathClaims.Path<SimDataset.DataTrack> path = path(platform(1, 2, false), plain(2, 3, 400, false));
		final RunReservations reservations = new RunReservations();
		final Authority waiting = new Authority(1, reservations);
		waiting.update(path, path, 0, 30, 430, null, 0);
		assertTrue(waiting.isGrantedBeyond(30));
		waiting.update(path, path, 0, 30, 30, null, 0);
		assertFalse(waiting.isGrantedBeyond(30));
		final Authority passing = new Authority(2, reservations);
		final PathClaims.Path<SimDataset.DataTrack> other = path(platform(4, 2, false), plain(2, 3, 400, false));
		for (int i = 0; i < 3; i++) tick(reservations, () -> waiting.update(path, path, 0, 30, 30, null, 0), () -> passing.update(other, other, 0, 30, 430, null, 0));
		assertEquals(430, passing.getRecordEnd(), 1E-6);
	}

	@Test
	void aTrainBehindIsLetOnUpToTheOneAhead() {
		// Three sections of 400 and a platform
		final PathClaims.Path<SimDataset.DataTrack> path = path(plain(1, 2, 1200, false), platform(2, 3, false));
		final RunReservations reservations = new RunReservations();
		final Authority ahead = new Authority(1, reservations);
		final Authority behind = new Authority(2, reservations);
		final double[] ends = new double[2];
		final double[] aheadAt = {900};
		for (int i = 0; i < 6; i++) {
			tick(reservations, () -> ends[0] = ahead.update(path, path, aheadAt[0] - 100, aheadAt[0], 1230, null, 0), () -> ends[1] = behind.update(path, path, 0, 100, 1230, null, 0));
		}
		// The one ahead has the last section and the platform; the one behind gets the first two sections only
		assertEquals(1230, ends[0], 1E-6);
		assertEquals(800, ends[1], 1E-6);
		// Once the one ahead has gone, the way is clear
		ahead.release();
		for (int i = 0; i < 4; i++) {
			tick(reservations, () -> ends[1] = behind.update(path, path, 0, 100, 1230, null, 0));
		}
		assertEquals(1230, ends[1], 1E-6);
	}

	@Test
	void aTrainTurningBackKeepsTheOneBehindOutOfItsPlatform() {
		final SimDataset.DataTrack terminal = platform(6, 7, true);
		final SimDataset.DataTrack back = new SimDataset.DataTrack(7, 6, 30, true, false, 1, 0, 0, 1, false, true, true);
		final PathClaims.Path<SimDataset.DataTrack> path = path(plain(5, 6, 300, false), terminal, back, plain(6, 15, 300, false));
		final RunReservations reservations = new RunReservations();
		final Authority turning = new Authority(1, reservations);
		final Authority behind = new Authority(2, reservations);
		final double[] ends = new double[2];
		// The first stands at the platform, wanting to leave down the other track
		for (int i = 0; i < 6; i++) {
			tick(reservations, () -> ends[0] = turning.update(path, path, 300, 330, 660, null, 0), () -> ends[1] = behind.update(path, path, 0, 100, 330, null, 0));
		}
		assertEquals(660, ends[0], 1E-6, "the turning train gets its way out");
		assertEquals(300, ends[1], 1E-6, "the one behind waits short of the platform");
		assertTrue(turning.isGrantedBeyond(330));
	}

	@Test
	void aSingleTrackIsNeverGivenBothWays() {
		final PathClaims.Path<SimDataset.DataTrack> east = path(plain(1, 2, 100, false), plain(2, 3, 300, true), plain(3, 4, 100, false));
		final PathClaims.Path<SimDataset.DataTrack> west = path(plain(4, 3, 100, false), plain(3, 2, 300, true), plain(2, 1, 100, false));
		final RunReservations reservations = new RunReservations();
		final Authority eastbound = new Authority(1, reservations);
		final Authority westbound = new Authority(2, reservations);
		final double[] ends = new double[2];
		for (int i = 0; i < 6; i++) {
			tick(reservations, () -> ends[0] = eastbound.update(east, east, 0, 90, 500, null, 0), () -> ends[1] = westbound.update(west, west, 0, 90, 500, null, 0));
		}
		// One of them has the single track and goes through; the other waits at its end
		final boolean eastFirst = ends[0] > 100;
		assertNotEquals(eastFirst, ends[1] > 100, "only one way at a time: " + ends[0] + " " + ends[1]);
		assertEquals(100, eastFirst ? ends[1] : ends[0], 1E-6);
	}
}
