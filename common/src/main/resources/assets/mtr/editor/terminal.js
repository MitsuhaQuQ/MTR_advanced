"use strict";
/**
 * Which service a train runs next after the end of a route, and where it stands meanwhile, worked out apart from the page
 * so it can be checked on its own; the game's conflict check pairs the same way (transitsim.plan.TerminalPairs).
 *
 * In order: a pair the player set (a service carrying on from its last stop onto another's first), then one after the
 * other in a duty, then the nearest service leaving the station after the train arrives (inferred). A train turning back
 * at the platform it arrived at stays there until its next service leaves; with platforms to turn back at in turn (站前
 * 折返) it takes the first one free, as the game's trains choose; with none free, a turn back rail beyond the platform it
 * can wait on (混合折返). Times are milliseconds of the operating day.
 */
(function (root) {
	/** The least time between a train leaving its last stop and its next service, as the planner takes it. */
	const MIN_TURN = 30000;
	/** A train waiting this long or more for its next service waits on a free turn back rail instead (混合折返). */
	const LONG_WAIT = 5 * 60 * 1000;
	/** The longest a train waits for its next service before it counts as going back to the depot. */
	const MAX_WAIT = 2 * 60 * 60 * 1000;

	/**
	 * @param input.runs      the services of the day: { id, platforms, stations, times: [[arrival, departure]...],
	 *                        first, last (the stops served), suspended, returns, terminalPlatforms }
	 * @param input.manual    by service, the service the player set it to run next
	 * @param input.duties    each a train's services of the day, in order
	 * @param input.turnTimes from platform → to platform → the time a change of platform takes; a platform to itself, the
	 *                        time round a turn back rail beyond it
	 * @return { next, previous: Map id → pair, warnings: [] }; a pair is { from, to, kind: "same" | "change" | "hold",
	 * source: "manual" | "duty" | "nearest", platform, fromDeparture, toArrival, moved }
	 */
	function pair(input) {
		const runs = new Map(input.runs.map(run => [run.id, run]));
		const turnTimes = input.turnTimes || {};
		const next = new Map(), previous = new Map(), warnings = [];
		const ends = run => run && !run.suspended && !run.returns && run.last === run.platforms.length - 1;
		const starts = run => run && !run.suspended && run.first === 0;
		const arrival = run => run.times[run.last][0], leaving = run => run.times[run.last][1];
		const departure = run => run.times[0][1], firstArrival = run => run.times[0][0];
		const turnTime = (from, to) => (turnTimes[from] || {})[to];

		const link = (from, to, source) => {
			if (!ends(from) || !starts(to) || next.has(from.id) || previous.has(to.id) || from.id === to.id) return;
			const fromPlatform = from.platforms[from.last], toPlatform = to.platforms[0];
			if (from.stations[from.last] !== to.stations[0] && fromPlatform !== toPlatform) return;
			const pairing = { from, to, kind: fromPlatform === toPlatform ? "same" : "change", source, platform: fromPlatform, moved: false };
			next.set(from.id, pairing);
			previous.set(to.id, pairing);
		};
		// Set by the player, then one after the other in a duty
		Object.entries(input.manual || {}).forEach(([from, to]) => link(runs.get(from), runs.get(to), "manual"));
		(input.duties || []).forEach(ids => {
			for (let i = 0; i + 1 < ids.length; i++) link(runs.get(ids[i]), runs.get(ids[i + 1]), "duty");
		});
		// The nearest: from the same platform first, then another of the station the train can get to in time
		[...runs.values()].filter(run => ends(run) && !next.has(run.id)).sort((a, b) => arrival(a) - arrival(b)).forEach(from => {
			const station = from.stations[from.last], platform = from.platforms[from.last];
			let best = null, bestSame = false;
			runs.forEach(to => {
				if (!starts(to) || previous.has(to.id) || to.id === from.id || to.stations[0] !== station) return;
				const same = to.platforms[0] === platform;
				const ready = leaving(from) + MIN_TURN + (same ? 0 : turnTime(platform, to.platforms[0]) === undefined ? Infinity : turnTime(platform, to.platforms[0]));
				if (ready > firstArrival(to) || departure(to) - arrival(from) > MAX_WAIT) return;
				if (!best || same && !bestSame || same === bestSame && departure(to) < departure(best)) {
					best = to;
					bestSame = same;
				}
			});
			if (best) link(from, best, "nearest");
		});

		// Where each train turning back at its platform stands, in the order they arrive: its own platform, or the next one
		// of those it may turn back at that is free; or a turn back rail beyond it; or, with neither, its own, in conflict
		const taken = new Map(), holds = new Map();
		const free = (list, start, end) => !(list || []).some(other => other.start < end + MIN_TURN && start < other.end + MIN_TURN);
		// The stops of trains that don't turn back there take the platform as they stop
		input.runs.forEach(run => {
			if (run.suspended) return;
			for (let i = run.first; i <= run.last; i++) {
				const turning = i === run.last && next.has(run.id) && next.get(run.id).kind === "same" || i === run.first && previous.has(run.id) && previous.get(run.id).kind === "same";
				if (!turning) add(taken, run.platforms[i], { start: run.times[i][0], end: run.times[i][1], id: run.id });
			}
		});
		[...next.values()].filter(pairing => pairing.kind === "same").sort((a, b) => arrival(a.from) - arrival(b.from)).forEach(pairing => {
			const { from, to } = pairing;
			const start = arrival(from), end = departure(to);
			const choices = [from.platforms[from.last], ...(from.terminalPlatforms || []).filter(platform => platform !== from.platforms[from.last])];
			const own = choices[0];
			// Waiting on a turn back rail beyond the platform, which takes it only to set down and to pick up: first for a
			// long wait, as the game's trains do, otherwise when no platform is free
			const round = turnTime(own, own);
			const onRail = () => {
				if (round === undefined) return false;
				const runIn = round / 2, runOut = round - runIn;
				const railStart = leaving(from) + runIn, railEnd = firstArrival(to) - runOut;
				if (railStart > railEnd || !free(taken.get(own), start, leaving(from)) || !free(taken.get(own), firstArrival(to), end) || !free(holds.get(own), railStart, railEnd)) return false;
				pairing.kind = "hold";
				pairing.railStart = railStart;
				pairing.railEnd = railEnd;
				add(holds, own, { start: railStart, end: railEnd, id: from.id });
				add(taken, own, { start, end: leaving(from), id: from.id });
				add(taken, own, { start: firstArrival(to), end, id: to.id });
				return true;
			};
			if (end - start >= LONG_WAIT && onRail()) return;
			const chosen = choices.find(platform => free(taken.get(platform), start, end));
			if (chosen !== undefined) {
				pairing.platform = chosen;
				pairing.moved = chosen !== own;
				add(taken, chosen, { start, end, id: from.id, pairing });
				if (pairing.moved) blockedBy(taken.get(own), start, end).forEach(other => other.pairing && (other.pairing.displaced = (other.pairing.displaced || 0) + 1));
				return;
			}
			if (onRail()) {
				blockedBy(taken.get(own), start, end).forEach(other => other.pairing && (other.pairing.displaced = (other.pairing.displaced || 0) + 1));
				return;
			}
			pairing.conflict = true;
			add(taken, own, { start, end, id: from.id, pairing });
			warnings.push({ type: "NO_FREE_PLATFORM", from: from.id, to: to.id, platform: own, at: start });
		});
		// A train standing long enough that others had to take another platform or a rail holds its platform (跨时刻接续)
		next.forEach(pairing => {
			if (pairing.displaced) warnings.push({ type: "HOLDS_PLATFORM", from: pairing.from.id, to: pairing.to.id, platform: pairing.platform, at: arrival(pairing.from), until: departure(pairing.to), count: pairing.displaced });
		});

		next.forEach(pairing => {
			pairing.fromDeparture = pairing.kind === "same" ? departure(pairing.to) : leaving(pairing.from);
			pairing.toArrival = pairing.kind === "same" ? arrival(pairing.from) : firstArrival(pairing.to);
		});
		return { next, previous, warnings };
	}

	function add(map, key, entry) {
		if (!map.has(key)) map.set(key, []);
		map.get(key).push(entry);
	}

	function blockedBy(list, start, end) {
		return (list || []).filter(other => other.start < end + MIN_TURN && start < other.end + MIN_TURN);
	}

	const api = { MIN_TURN, LONG_WAIT, MAX_WAIT, pair };
	if (typeof module !== "undefined" && module.exports) module.exports = api;
	else root.TerminalPairs = api;
})(typeof window !== "undefined" ? window : this);
