package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A depot's rules for going out and back and its named deadhead paths (运用补完 step 2): which rule a service goes by, read
 * and written alike, and a duty's own exceptions kept when it is planned again.
 */
class DepotRulesTest {

	private static DepotRules.Rule rule(long id, int weekdays, String... lines) {
		final DepotRules.Rule rule = new DepotRules.Rule();
		rule.id = id;
		rule.weekdays = weekdays;
		rule.lineKeys.addAll(Arrays.asList(lines));
		return rule;
	}

	@Test
	void theMostParticularRuleIsTaken() {
		final DepotRules rules = new DepotRules();
		rules.rules.add(rule(1, 0));
		rules.rules.add(rule(2, 0b1100000));
		rules.rules.add(rule(3, 0, "A"));
		rules.rules.add(rule(4, 0b1100000, "A"));
		rules.rules.add(rule(5, 0b1100000, "A"));
		// Line A at the weekend: the rule for both, the first of two alike
		assertEquals(4, rules.match("A", 5).id);
		// Line A on Monday: for its line
		assertEquals(3, rules.match("A", 0).id);
		// Line B at the weekend: for the weekday
		assertEquals(2, rules.match("B", 6).id);
		// Line B on Monday: for all
		assertEquals(1, rules.match("B", 0).id);
		assertNull(new DepotRules().match("A", 0));
	}

	@Test
	void rulesAndPathsReadBack() {
		final DepotRules rules = new DepotRules();
		final DepotRules.Rule rule = rule(7, 0b11111, "TRAIN|一号线", "TRAIN|二号线");
		rule.outEntrance = 12;
		rule.backEntrance = 13;
		rule.extraLeadMillis = 30000;
		rule.backSiding = 99;
		rule.outPath = 5;
		rule.waitThresholdMillis = 600000;
		rules.rules.add(rule);
		final DepotRules.DeadheadPath path = new DepotRules.DeadheadPath();
		path.id = 5;
		path.name = "12 站上行出库";
		path.kind = DepotRules.DeadheadPath.OUT;
		path.from = 99;
		path.to = 1201;
		path.waypoints = new long[]{123456789L, -987654321L};
		rules.paths.add(path);

		final DepotRules read = new DepotRules();
		read.fromBytes(rules.toBytes());
		assertArrayEquals(rules.toBytes(), read.toBytes());
		assertEquals(600000, read.rules.get(0).waitThresholdMillis);
		assertEquals("12 站上行出库", read.getPath(5).name);
		assertArrayEquals(path.waypoints, read.getPath(5).waypoints);
		// What doesn't read leaves none
		final DepotRules broken = new DepotRules();
		broken.fromBytes(new byte[]{9, 9});
		assertTrue(broken.isEmpty());
	}

	@Test
	void aDutysOwnExceptionsAreKeptWhenPlannedAgain() {
		final DepotDuties.DayDuty old = new DepotDuties.DayDuty();
		old.segments.add(new DepotDuties.Segment(1, 0, 0, -1));
		old.segments.add(new DepotDuties.Segment(2, 1800000, 0, -1));
		old.waits.add(DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL);
		old.outLeadMillis = 300000;
		old.returnSidingId = 8;
		old.backPathId = 4;
		old.exceptions = DepotDuties.DayDuty.EXCEPTION_OUT_LEAD | DepotDuties.DayDuty.EXCEPTION_WAITS;

		final DepotDuties.DayDuty planned = new DepotDuties.DayDuty();
		planned.segments.addAll(old.segments);
		planned.waits.add(DepotDuties.DayDuty.WAIT_PLATFORM);
		planned.outLeadMillis = 60000;
		planned.returnSidingId = 9;
		planned.keepExceptions(old);
		// The exceptions as set, the rest as planned
		assertEquals(300000, planned.outLeadMillis);
		assertEquals(Arrays.asList(DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL), planned.waits);
		assertEquals(9, planned.returnSidingId);
		assertEquals(0, planned.backPathId);
		// Waits set for other services can't be kept: they go by the rules again
		final DepotDuties.DayDuty longer = new DepotDuties.DayDuty();
		longer.segments.addAll(old.segments);
		longer.segments.add(new DepotDuties.Segment(1, 3600000, 0, -1));
		longer.waits.addAll(Arrays.asList(DepotDuties.DayDuty.WAIT_PLATFORM, DepotDuties.DayDuty.WAIT_PLATFORM));
		longer.keepExceptions(old);
		assertEquals(Arrays.asList(DepotDuties.DayDuty.WAIT_PLATFORM, DepotDuties.DayDuty.WAIT_PLATFORM), longer.waits);
		assertEquals(DepotDuties.DayDuty.EXCEPTION_OUT_LEAD, longer.exceptions);

		// Saved and read with them
		final DepotDuties.DayDuty text = DepotDuties.DayDuty.fromText(planned.toText());
		assertNotNull(text);
		assertTrue(text.sameAs(planned));
	}
}
