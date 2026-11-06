"use strict";
/**
 * Timetable batch editing worked out apart from the page, so it can be checked on its own: services spread evenly
 * between two times, a run of services spaced again, a service put in beside another, and a run of services chosen.
 * Times are milliseconds in the day; a time past midnight wraps round.
 */
(function (root) {
	const DAY = 24 * 60 * 60 * 1000;
	const mod = (a, b) => ((a % b) + b) % b;

	/**
	 * @return {number[]} count times from start to end, both included and evenly apart; one count is the start alone.
	 * An end before the start runs on past midnight.
	 */
	function evenTimes(start, end, count) {
		if (!(count >= 1)) return [];
		if (count === 1) return [mod(start, DAY)];
		const span = mod(end - start, DAY) || DAY;
		const times = [];
		for (let i = 0; i < count; i++) times.push(mod(start + Math.round(span * i / (count - 1) / 1000) * 1000, DAY));
		return times;
	}

	/**
	 * @return {number[]} from start every step until end, the end included where a service falls on it; at most 1000.
	 * An end before the start runs on past midnight.
	 */
	function stepTimes(start, end, step) {
		if (!(step > 0)) return [];
		const span = mod(end - start, DAY);
		const times = [];
		for (let offset = 0; offset <= span && times.length < 1000; offset += step) times.push(mod(start + offset, DAY));
		return times;
	}

	/**
	 * @param times the services' times, in their order
	 * @return {number[]} the times spaced gap apart from the first, which keeps its time
	 */
	function respace(times, gap) {
		return times.map((time, i) => mod(times[0] + i * gap, DAY));
	}

	/**
	 * A new service beside one: between it and its neighbour on that side, halfway; with no neighbour there, as far
	 * beyond it as the neighbour on the other side is; with neither, 10 minutes.
	 *
	 * @param side -1 for before it, 1 for after
	 * @param before the time of the service before it, or null
	 * @param after the time of the service after it, or null
	 */
	function besideTime(time, side, before, after) {
		const near = side < 0 ? before : after, far = side < 0 ? after : before;
		if (near !== null && near !== undefined) {
			const gap = side < 0 ? mod(time - near, DAY) : mod(near - time, DAY);
			return mod(time + side * Math.round(gap / 2 / 1000) * 1000, DAY);
		}
		const gap = far !== null && far !== undefined ? (side < 0 ? mod(far - time, DAY) : mod(time - far, DAY)) : 10 * 60000;
		return mod(time + side * gap, DAY);
	}

	/**
	 * @param ids the services in their order
	 * @return {string[]} the run from one to the other, whichever comes first, with nothing skipped; empty if either is missing
	 */
	function runBetween(ids, from, to) {
		const a = ids.indexOf(from), b = ids.indexOf(to);
		if (a < 0 || b < 0) return [];
		return ids.slice(Math.min(a, b), Math.max(a, b) + 1);
	}

	const api = { DAY, evenTimes, stepTimes, respace, besideTime, runBetween };
	if (typeof module !== "undefined" && module.exports) module.exports = api;
	else root.TimetableBatch = api;
})(typeof window !== "undefined" ? window : this);
