package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The track section layer (stages document round 1): updating it piece by piece gives what building it from scratch
 * gives, whatever the order; it cuts where the network the simulation runs on cuts; saved sections come back as they
 * were and are repaired where the rails changed; the signal layer cuts as {@link BlockSections} does.
 */
class TrackSectionsTest {

	private static final int[] SPEEDS = {25, 40, 60, 80, 120, 160};

	/**
	 * The rails of a generated network as the layer takes them, each with a speed label of its own.
	 */
	private static Map<UUID, TrackSections.Rail> railsOf(LegalNetworks network) {
		final Map<UUID, List<RailGraph.Connection<String>>> byKey = new TreeMap<>();
		network.graph().connections.forEach(connection -> byKey.computeIfAbsent(RailKeys.of(connection.fromPos, connection.toPos), key -> new ArrayList<>()).add(connection));
		final Map<UUID, TrackSections.Rail> rails = new HashMap<>();
		byKey.forEach((key, connections) -> rails.put(key, railOf(key, connections, SPEEDS[Math.floorMod(key.hashCode(), SPEEDS.length)])));
		return rails;
	}

	private static TrackSections.Rail railOf(UUID key, List<RailGraph.Connection<String>> connections, int speed) {
		final long node1 = key.getMostSignificantBits();
		final long node2 = key.getLeastSignificantBits();
		boolean forwards = false;
		boolean backwards = false;
		boolean platform = false;
		double length = 0;
		for (final RailGraph.Connection<String> connection : connections) {
			final boolean runs = connection.kind != RailGraph.Kind.DISABLED;
			if (connection.fromPos == node1) {
				forwards |= runs;
			} else {
				backwards |= runs;
			}
			platform |= connection.kind == RailGraph.Kind.PLATFORM;
			length = connection.cost;
		}
		return new TrackSections.Rail(node1, node2, length, 0, platform ? 80 : speed, !platform, platform ? SectionType.PLATFORM : SectionType.NORMAL, forwards, backwards, platform ? key.hashCode() | 1 : 0);
	}

	private static TrackSections build(Map<UUID, TrackSections.Rail> rails, Map<Long, Integer> marks) {
		final TrackSections sections = new TrackSections();
		sections.sync(rails, marks);
		return sections;
	}

	private static Map<Long, String> contents(TrackSections sections) {
		final Map<Long, String> contents = new TreeMap<>();
		sections.getSections().forEach((hash, section) -> contents.put(hash, Arrays.toString(section.toBytes())));
		return contents;
	}

	private static void assertEveryRailInOneSection(TrackSections sections, Map<UUID, TrackSections.Rail> rails) {
		int count = 0;
		for (final TrackSections.Section section : sections.getSections().values()) {
			count += section.getRailCount();
			for (int i = 0; i < section.getRailCount(); i++) {
				assertEquals(section.hash, sections.getSectionOf(section.getRail(i).getKey()).longValue());
			}
		}
		assertEquals(rails.size(), count);
	}

	@Test
	void theOrderRailsAreAddedInDoesNotChangeTheSections() {
		for (long seed = 0; seed < 20; seed++) {
			final Map<UUID, TrackSections.Rail> rails = railsOf(LegalNetworks.generate(seed));
			final TrackSections whole = build(rails, Collections.emptyMap());
			assertEveryRailInOneSection(whole, rails);

			// One rail at a time, in a shuffled order
			final List<TrackSections.Rail> shuffled = new ArrayList<>(rails.values());
			Collections.shuffle(shuffled, new Random(seed));
			final TrackSections oneByOne = new TrackSections();
			shuffled.forEach(rail -> oneByOne.update(Collections.singletonList(rail), Collections.emptyList(), Collections.emptyMap()));
			assertEquals(contents(whole), contents(oneByOne), "seed " + seed);
			assertEquals(0, oneByOne.getCollisions());
		}
	}

	@Test
	void changingRailsBitByBitGivesWhatBuildingFromScratchGives() {
		for (long seed = 0; seed < 20; seed++) {
			final Random random = new Random(seed);
			final Map<UUID, TrackSections.Rail> all = railsOf(LegalNetworks.generate(seed));
			final List<UUID> keys = new ArrayList<>(new TreeSet<>(all.keySet()));
			final List<Long> nodes = new ArrayList<>();
			keys.forEach(key -> nodes.add(key.getMostSignificantBits()));

			final Map<UUID, TrackSections.Rail> current = new HashMap<>(all);
			final Map<Long, Integer> marks = new HashMap<>();
			final TrackSections sections = build(current, marks);
			for (int step = 0; step < 60; step++) {
				final List<TrackSections.Rail> put = new ArrayList<>();
				final List<UUID> removed = new ArrayList<>();
				final Map<Long, Integer> markChanges = new HashMap<>();
				// Each rail changed at most once a step, as an update takes its removals before its additions
				final Set<UUID> changed = new HashSet<>();
				for (int i = 1 + random.nextInt(4); i > 0; i--) {
					final UUID key = keys.get(random.nextInt(keys.size()));
					if (!changed.add(key)) {
						continue;
					}
					switch (random.nextInt(4)) {
						case 0:
							// Taken away, or laid again
							if (current.remove(key) != null) {
								removed.add(key);
							} else {
								current.put(key, all.get(key));
								put.add(all.get(key));
							}
							break;
						case 1:
							// Another speed
							final TrackSections.Rail rail = current.get(key);
							if (rail != null) {
								final TrackSections.Rail faster = new TrackSections.Rail(rail.node1, rail.node2, rail.length, rail.shape, SPEEDS[random.nextInt(SPEEDS.length)], rail.canAccelerate, rail.type, rail.forwards, rail.backwards, rail.savedRailId);
								current.put(key, faster);
								put.add(faster);
							}
							break;
						default:
							// A key node or a boundary set or taken away
							final long node = nodes.get(random.nextInt(nodes.size()));
							final int mark = random.nextInt(3);
							markChanges.put(node, mark);
							if (mark == 0) {
								marks.remove(node);
							} else {
								marks.put(node, mark);
							}
					}
				}
				sections.update(put, removed, markChanges);
				final TrackSections fresh = build(current, marks);
				assertEquals(contents(fresh), contents(sections), "seed " + seed + " step " + step);
				assertEveryRailInOneSection(sections, current);
			}
		}
	}

	@Test
	void sectionsWithTheSameHashGetTheSameHashesWhateverTheOrder() throws Exception {
		// A hash with only four values, so most sections share one
		TrackSections.hasher = data -> TrackSections.hash(data) & 3;
		try {
			for (long seed = 0; seed < 5; seed++) {
				final Map<UUID, TrackSections.Rail> rails = railsOf(LegalNetworks.generate(seed));
				final TrackSections whole = build(rails, Collections.emptyMap());
				assertTrue(whole.getCollisions() > 0);
				assertEveryRailInOneSection(whole, rails);

				final List<TrackSections.Rail> shuffled = new ArrayList<>(rails.values());
				Collections.shuffle(shuffled, new Random(seed));
				final TrackSections oneByOne = new TrackSections();
				shuffled.forEach(rail -> oneByOne.update(Collections.singletonList(rail), Collections.emptyList(), Collections.emptyMap()));
				assertEquals(contents(whole), contents(oneByOne), "seed " + seed);

				// Taking rails away renumbers what is left as building without them does
				final Map<UUID, TrackSections.Rail> fewer = new HashMap<>(rails);
				final List<UUID> removed = new ArrayList<>();
				shuffled.subList(0, shuffled.size() / 3).forEach(rail -> removed.add(rail.getKey()));
				removed.forEach(fewer::remove);
				oneByOne.update(Collections.emptyList(), removed, Collections.emptyMap());
				assertEquals(contents(build(fewer, Collections.emptyMap())), contents(oneByOne), "seed " + seed);

				// And a save comes back with the same hashes
				final List<TrackSections.Section> saved = new ArrayList<>();
				for (final TrackSections.Section section : whole.getSections().values()) {
					saved.add(TrackSections.Section.fromBytes(section.hash, section.toBytes()));
				}
				Collections.shuffle(saved, new Random(seed));
				final TrackSections restored = new TrackSections();
				assertTrue(restored.restore(saved, rails, Collections.emptyMap()).isEmpty());
				assertEquals(contents(whole), contents(restored));
			}
		} finally {
			TrackSections.hasher = TrackSections::hash;
		}
	}

	@Test
	void anUpdateReportsOnlyTheSectionsThatChanged() {
		final Map<UUID, TrackSections.Rail> rails = railsOf(LegalNetworks.generate(3));
		final TrackSections sections = build(rails, Collections.emptyMap());
		final Map<Long, String> before = contents(sections);
		final UUID key = new TreeSet<>(rails.keySet()).first();
		final TrackSections.Rail rail = rails.get(key);
		final TrackSections.Rail changed = new TrackSections.Rail(rail.node1, rail.node2, rail.length, rail.shape, rail.speedKmh + 1, rail.canAccelerate, rail.type, rail.forwards, rail.backwards, rail.savedRailId);
		final TrackSections.Change change = sections.update(Collections.singletonList(changed), Collections.emptyList(), Collections.emptyMap());
		final Map<Long, String> after = contents(sections);
		final Set<Long> gone = new TreeSet<>(before.keySet());
		gone.removeAll(after.keySet());
		final Set<Long> made = new TreeSet<>(after.keySet());
		made.removeAll(before.keySet());
		assertEquals(gone, change.removed);
		assertEquals(made, change.added);
		// The rail's own section, nothing else
		assertEquals(1, change.removed.size());
		assertEquals(1, change.added.size());
		// Setting it back gives the old hash back
		final TrackSections.Change back = sections.update(Collections.singletonList(rail), Collections.emptyList(), Collections.emptyMap());
		assertEquals(change.added, back.removed);
		assertEquals(change.removed, back.added);
		assertEquals(before, contents(sections));
	}

	@Test
	void itCutsWhereTheNetworkTheSimulationRunsOnCuts() {
		final RailAttributes<String> attributes = new RailAttributes<String>() {
			@Override
			public SectionType getType(String rail) {
				return SectionType.NORMAL;
			}

			@Override
			public boolean canAccelerate(String rail) {
				return true;
			}

			@Override
			public double getSpeedLimit(String rail) {
				return 1;
			}

			@Override
			public double getLength(String rail) {
				return 1;
			}
		};
		for (long seed = 0; seed < 10; seed++) {
			final LegalNetworks network = LegalNetworks.generate(seed);
			final Map<UUID, TrackSections.Rail> rails = railsOf(network);
			final Map<UUID, Long> savedRails = new HashMap<>();
			rails.forEach((key, rail) -> {
				if (rail.savedRailId != 0) {
					savedRails.put(key, rail.savedRailId);
				}
			});
			// Boundaries cut both
			final Set<Long> boundaryNodes = new TreeSet<>();
			new TreeSet<>(rails.keySet()).stream().limit(5).forEach(key -> boundaryNodes.add(key.getLeastSignificantBits()));
			final Boundaries boundaries = new Boundaries();
			boundaries.nodes.addAll(boundaryNodes);
			final Map<Long, Integer> marks = new HashMap<>();
			boundaryNodes.forEach(node -> marks.put(node, TrackSections.MARK_BOUNDARY));

			final Set<String> expected = new TreeSet<>();
			NetworkBuilder.build(network.graph(), null, boundaries, savedRails, attributes, 1).sections.values().forEach(section -> expected.add(Arrays.toString(section.getNodes())));
			final Set<String> actual = new TreeSet<>();
			build(rails, marks).getSections().values().forEach(section -> actual.add(Arrays.toString(section.getNodes())));
			assertEquals(expected, actual, "seed " + seed);
		}
	}

	@Test
	void savedSectionsComeBackAndAreRepairedWhereTheRailsChanged() throws Exception {
		final Map<UUID, TrackSections.Rail> rails = railsOf(LegalNetworks.generate(5));
		final Map<Long, Integer> marks = new HashMap<>();
		final long marked = new TreeSet<>(rails.keySet()).last().getMostSignificantBits();
		marks.put(marked, TrackSections.MARK_KEY);
		final TrackSections original = build(rails, marks);
		final List<TrackSections.Section> saved = new ArrayList<>();
		for (final TrackSections.Section section : original.getSections().values()) {
			saved.add(TrackSections.Section.fromBytes(section.hash, section.toBytes()));
		}

		// As saved
		final TrackSections same = new TrackSections();
		assertTrue(same.restore(saved, rails, marks).isEmpty());
		assertEquals(contents(original), contents(same));

		// A rail gone and the mark taken away while the save was away
		final Map<UUID, TrackSections.Rail> fewer = new HashMap<>(rails);
		fewer.remove(new TreeSet<>(rails.keySet()).first());
		final TrackSections repaired = new TrackSections();
		final TrackSections.Change change = repaired.restore(saved, fewer, Collections.emptyMap());
		assertFalse(change.isEmpty());
		assertEquals(contents(build(fewer, Collections.emptyMap())), contents(repaired));

		// A save cut where the rules don't cut, as by an older version: the mark is kept in the sections but not given
		final TrackSections recut = new TrackSections();
		recut.restore(saved, rails, Collections.emptyMap());
		assertEquals(contents(build(rails, Collections.emptyMap())), contents(recut));
	}

	@Test
	void sectionBytesComeBackTheSame() throws Exception {
		final TrackSections sections = build(railsOf(LegalNetworks.generate(7)), Collections.emptyMap());
		for (final TrackSections.Section section : sections.getSections().values()) {
			final TrackSections.Section read = TrackSections.Section.fromBytes(section.hash, section.toBytes());
			assertTrue(section.sameContent(read));
			assertEquals(TrackSections.hash(section.toBytes()), section.hash);
			assertEquals(section.isForwards(), read.isForwards());
			assertEquals(section.isBackwards(), read.isBackwards());
		}
	}

	@Test
	void speedLabelsGiveAPhysicalLimitAndAPlannedSpeed() {
		assertEquals(85, SpeedLimits.physicalKmh(80));
		assertEquals(80, SpeedLimits.planKmh(80));
		assertEquals(65, SpeedLimits.physicalKmh(60));
		assertEquals(60, SpeedLimits.planKmh(60));
		assertEquals(45, SpeedLimits.physicalKmh(45));
		assertEquals(40, SpeedLimits.planKmh(45));
		assertEquals(25, SpeedLimits.physicalKmh(25));
		assertEquals(20, SpeedLimits.planKmh(25));
		assertEquals(1, SpeedLimits.planKmh(2));
		final TrackSections.Rail rail = new TrackSections.Rail(1, 2, 10, 0, 120, true, SectionType.NORMAL, true, true, 0);
		assertEquals(125, rail.getPhysicalKmh());
		assertEquals(120, rail.getPlanKmh());
	}

	@Test
	void theSignalLayerCutsAsBlockSectionsDo() {
		final Map<UUID, TrackSections.Rail> rails = railsOf(LegalNetworks.generate(9));
		// Long rails, so some are cut
		final Map<UUID, TrackSections.Rail> longRails = new HashMap<>();
		rails.forEach((key, rail) -> longRails.put(key, new TrackSections.Rail(rail.node1, rail.node2, rail.length * 20, rail.shape, rail.speedKmh, rail.canAccelerate, rail.type, rail.forwards, rail.backwards, rail.savedRailId)));
		final TrackSections sections = build(longRails, Collections.emptyMap());
		final List<UUID> keys = new ArrayList<>(new TreeSet<>(longRails.keySet()));
		final Random random = new Random(9);
		final Map<Long, List<UUID>> blocks = new TreeMap<>();
		for (long block = 1; block <= 6; block++) {
			final List<UUID> blockRails = new ArrayList<>();
			for (int i = 0; i < 15; i++) {
				blockRails.add(keys.get(random.nextInt(keys.size())));
			}
			blocks.put(block, blockRails);
		}
		final BlockSections blockSections = new BlockSections(new ArrayList<>(blocks.values()), new BlockSections.RailInfo() {
			@Override
			public double getLength(UUID rail) {
				return longRails.get(rail).length;
			}

			@Override
			public float getMaxSpeed(UUID rail) {
				return SignalOverlay.getCutSpeed(longRails.get(rail));
			}
		});
		final Map<Long, SignalOverlay.Entry> overlay = SignalOverlay.build(sections.getSections(), blocks);
		int cut = 0;
		for (final TrackSections.Section section : sections.getSections().values()) {
			final SignalOverlay.Entry entry = overlay.get(section.hash);
			for (int i = 0; i < section.getRailCount(); i++) {
				final UUID key = section.getRail(i).getKey();
				int pieces = 0;
				while (blockSections.getPiece(BlockSections.getPieceKey(key, pieces)) != null) {
					pieces++;
				}
				if (pieces == 0) {
					assertTrue(entry == null || entry.getPieces(i) == 1 && entry.getBlocks(i).length == 0);
				} else {
					assertEquals(pieces, entry.getPieces(i));
					final Set<Long> inBlocks = new TreeSet<>();
					blocks.forEach((block, blockRails) -> {
						if (blockRails.contains(key)) {
							inBlocks.add(block);
						}
					});
					assertArrayEquals(inBlocks.stream().mapToLong(Long::longValue).toArray(), entry.getBlocks(i));
					if (pieces > 1) {
						cut++;
					}
				}
			}
		}
		assertTrue(cut > 0);
		overlay.values().forEach(entry -> {
			try {
				assertEquals(entry, SignalOverlay.Entry.fromBytes(entry.toBytes()));
			} catch (Exception e) {
				fail(e);
			}
		});
	}
}
