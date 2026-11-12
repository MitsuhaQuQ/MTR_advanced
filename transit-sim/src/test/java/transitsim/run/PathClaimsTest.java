package transitsim.run;

import org.junit.jupiter.api.Test;
import transitsim.network.Resource;
import transitsim.sim.Arbiter;
import transitsim.sim.SimDataset;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The resources a train asks for along its path, in the groups they are granted in (run layer, 5.5).
 */
class PathClaimsTest {

	private static SimDataset.DataTrack plain(long from, long to, double length, boolean twoWay) {
		return new SimDataset.DataTrack(from, to, length, false, false, 0, 0, 0, 1, true, true, twoWay);
	}

	private static SimDataset.DataTrack platform(long from, long to, long platformId, boolean twoWay) {
		return new SimDataset.DataTrack(from, to, 30, true, false, platformId, 20, 0, 1, false, true, twoWay);
	}

	private static SimDataset.DataTrack siding(long from, long to) {
		return new SimDataset.DataTrack(from, to, 60, false, true, 100, 0, 0, 0.5F, false, false, true);
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

	private static long count(PathClaims.Group group, Resource.Kind kind) {
		return group.claims.stream().filter(claim -> claim.kind == kind).count();
	}

	@Test
	void eachSectionOfTheLineIsAGroupAndTheSidingNone() {
		// Siding 0-60, a plain track 60-260, a platform 260-290
		final PathClaims.Path<SimDataset.DataTrack> path = path(siding(0, 1), plain(1, 2, 200, false), platform(2, 3, 1, false));
		final List<PathClaims.Group> groups = PathClaims.between(path, 10, 290, null);
		assertEquals(2, groups.size(), groups.toString());
		assertEquals(260, groups.get(0).end, 1E-6);
		assertEquals(290, groups.get(1).end, 1E-6);
		groups.forEach(group -> assertEquals(1, count(group, Resource.Kind.TRACK)));
	}

	private static SimDataset.DataTrack runningSide(long from, long to, double length, int convention) {
		return new SimDataset.DataTrack(from, to, length, false, false, 0, 0, 0, 1, true, true, true, convention);
	}

	@Test
	void aDoubleTrackRunTheWayOfItsSideIsLetOnSectionBySection() {
		// A 1200-block two way track of a double track line, run the way its running side has it: three sections, each a group
		// of its own, so a train following another along it is let on as each clears; each with the track's direction
		final PathClaims.Path<SimDataset.DataTrack> path = path(runningSide(1, 2, 1200, SimDataset.DataTrack.CONVENTION_WITH));
		final List<PathClaims.Group> groups = PathClaims.between(path, 0, 1200, null);
		assertEquals(3, groups.size(), groups.toString());
		assertEquals(Arrays.asList(400.0, 800.0, 1200.0), Arrays.asList(groups.get(0).end, groups.get(1).end, groups.get(2).end));
		assertEquals(1, count(groups.get(0), Resource.Kind.SINGLE_TRACK));
		groups.forEach(group -> assertEquals(1, count(group, Resource.Kind.TRACK)));
	}

	@Test
	void againstItsSideOrOnASingleTrackTheWholeStretchAtOnce() {
		for (final int convention : new int[]{SimDataset.DataTrack.CONVENTION_AGAINST, SimDataset.DataTrack.CONVENTION_NONE}) {
			final PathClaims.Path<SimDataset.DataTrack> path = path(runningSide(1, 2, 1200, convention));
			final List<PathClaims.Group> groups = PathClaims.between(path, 0, 1200, null);
			assertEquals(1, groups.size(), convention + ": " + groups);
			assertEquals(3, count(groups.get(0), Resource.Kind.TRACK));
			assertEquals(1, count(groups.get(0), Resource.Kind.SINGLE_TRACK));
		}
	}

	@Test
	void aTrainAgainstTheSideAndOneWithItShareNoDirection() {
		// The way with the side and the way against it are the two ways of the same resource, so the arbiter never lets both on
		final PathClaims.Path<SimDataset.DataTrack> with = path(runningSide(1, 2, 1200, SimDataset.DataTrack.CONVENTION_WITH));
		final PathClaims.Path<SimDataset.DataTrack> against = path(runningSide(2, 1, 1200, SimDataset.DataTrack.CONVENTION_AGAINST));
		final Arbiter.Claim<UUID> withWay = PathClaims.between(with, 0, 1200, null).get(0).claims.stream().filter(claim -> claim.kind == Resource.Kind.SINGLE_TRACK).findFirst().get();
		final Arbiter.Claim<UUID> againstWay = PathClaims.between(against, 0, 1200, null).get(0).claims.stream().filter(claim -> claim.kind == Resource.Kind.SINGLE_TRACK).findFirst().get();
		assertEquals(withWay.resource, againstWay.resource);
		assertEquals(-withWay.way, againstWay.way);
	}

	@Test
	void aLongTrackIsCutIntoSectionsLetOnOneByOne() {
		final PathClaims.Path<SimDataset.DataTrack> path = path(plain(1, 2, 1200, false));
		final List<PathClaims.Group> groups = PathClaims.between(path, 0, 1200, null);
		assertEquals(3, groups.size(), groups.toString());
		// Each section lets the train run to its end
		assertEquals(Arrays.asList(400.0, 800.0, 1200.0), Arrays.asList(groups.get(0).end, groups.get(1).end, groups.get(2).end));
		// Only the sections a part runs along
		assertEquals(1, PathClaims.between(path, 100, 300, null).size());
		assertEquals(2, PathClaims.between(path, 300, 500, null).size());
	}

	@Test
	void aSingleTrackStretchIsOneGroupWithItsDirection() {
		final PathClaims.Path<SimDataset.DataTrack> path = path(plain(1, 2, 100, false), plain(2, 3, 100, true), platform(3, 4, 1, true), plain(4, 5, 100, true), plain(5, 6, 100, false));
		final List<PathClaims.Group> groups = PathClaims.between(path, 0, 430, null);
		assertEquals(3, groups.size(), groups.toString());
		assertEquals(3, count(groups.get(1), Resource.Kind.SINGLE_TRACK));
		assertEquals(3, count(groups.get(1), Resource.Kind.TRACK));
		assertEquals(330, groups.get(1).end, 1E-6);
		groups.get(1).claims.stream().filter(claim -> claim.kind == Resource.Kind.SINGLE_TRACK).forEach(claim -> assertNotEquals(0, claim.way));
	}

	@Test
	void aPlatformTurnedBackOnIsTakenBothWays() {
		final SimDataset.DataTrack terminal = platform(6, 7, 3, true);
		final SimDataset.DataTrack back = new SimDataset.DataTrack(7, 6, 30, true, false, 3, 0, 0, 1, false, true, true);
		final PathClaims.Path<SimDataset.DataTrack> path = path(plain(5, 6, 100, false), terminal, back, plain(6, 15, 100, false));
		final List<PathClaims.Group> groups = PathClaims.between(path, 0, 130, null);
		assertEquals(2, groups.size(), groups.toString());
		final List<Arbiter.Claim<java.util.UUID>> directions = new ArrayList<>();
		groups.get(1).claims.stream().filter(claim -> claim.kind == Resource.Kind.SINGLE_TRACK).forEach(directions::add);
		assertEquals(1, directions.size());
		assertEquals(0, directions.get(0).way, "both ways");
		assertEquals(130, groups.get(1).end, 1E-6);
	}

	@Test
	void theClaimsOfGroupsAreEachResourceOnce() {
		final UUID direction = PathClaims.directionOf(new UUID(1, 2));
		final PathClaims.Group forwards = new PathClaims.Group(Arrays.asList(Arbiter.Claim.track(new UUID(1, 2)), Arbiter.Claim.singleTrack(direction, true)), 10);
		final PathClaims.Group backwards = new PathClaims.Group(Arrays.asList(Arbiter.Claim.track(new UUID(1, 2)), Arbiter.Claim.singleTrack(direction, false)), 20);
		final List<Arbiter.Claim<UUID>> claims = PathClaims.claimsOf(Arrays.asList(forwards, backwards));
		assertEquals(2, claims.size());
		assertEquals(0, claims.get(1).way);
		assertNotEquals(new UUID(1, 2), direction);
	}
}
