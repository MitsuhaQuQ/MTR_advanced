// Checks the web editor's terminal pairing (assets/mtr/editor/terminal.js): node common/src/test/js/terminal.test.js
const assert = require("assert");
const path = require("path");
const T = require(path.join(__dirname, "../../main/resources/assets/mtr/editor/terminal.js"));
const M = 60000, H = 60 * M;

// Up 1 → 2 → A (station 12) and down A → 3 → 4, each stop two minutes on with twenty seconds' stop
const run = (id, platforms, stations, start, extra) => Object.assign({
	id, platforms, stations, first: 0, last: platforms.length - 1, suspended: false, returns: false, terminalPlatforms: [],
	times: platforms.map((_, i) => [start + i * 2 * M, start + i * 2 * M + 20000]),
}, extra || {});
const up = (id, start, extra) => run(id, [1, 2, 10], [1, 2, 12], start, extra);
const down = (id, start, extra) => run(id, [10, 3, 4], [12, 3, 4], start, extra);
const downB = (id, start, extra) => run(id, [11, 3, 4], [12, 3, 4], start, extra);

// The nearest from the same platform: up arrives at A at 6:04, down leaves A at 6:10
{
	const result = T.pair({ runs: [up("u1", 6 * H), down("d1", 6 * H + 10 * M)] });
	const p = result.next.get("u1");
	assert.strictEqual(p.to.id, "d1");
	assert.strictEqual(p.kind, "same");
	assert.strictEqual(p.source, "nearest");
	// The last stop's departure is the next service's, the next one's first arrival the train's own
	assert.strictEqual(p.fromDeparture, 6 * H + 10 * M + 20000);
	assert.strictEqual(p.toArrival, 6 * H + 4 * M);
	assert.strictEqual(result.previous.get("d1"), p);
	assert.deepStrictEqual(result.warnings, []);
}

// A duty pairs ahead of the nearest, and the player's choice ahead of the duty
{
	const runs = [up("u1", 6 * H), down("d1", 6 * H + 10 * M), down("d2", 6 * H + 20 * M)];
	assert.strictEqual(T.pair({ runs, duties: [["u1", "d2"]] }).next.get("u1").to.id, "d2");
	assert.strictEqual(T.pair({ runs, duties: [["u1", "d2"]] }).next.get("u1").source, "duty");
	const manual = T.pair({ runs, manual: { u1: "d1" }, duties: [["u1", "d2"]] });
	assert.strictEqual(manual.next.get("u1").to.id, "d1");
	assert.strictEqual(manual.next.get("u1").source, "manual");
}

// Another platform of the station only in time for the measured change of platform
{
	const runs = [up("u1", 6 * H), downB("d1", 6 * H + 8 * M)];
	assert.strictEqual(T.pair({ runs }).next.get("u1"), undefined, "no way measured");
	assert.strictEqual(T.pair({ runs, turnTimes: { 10: { 11: 2 * M } } }).next.get("u1").kind, "change");
	assert.strictEqual(T.pair({ runs, turnTimes: { 10: { 11: 5 * M } } }).next.get("u1"), undefined, "too late");
}

// Services that don't run, or go back to the depot, aren't paired
{
	assert.strictEqual(T.pair({ runs: [up("u1", 6 * H, { returns: true }), down("d1", 6 * H + 10 * M)] }).next.size, 0);
	assert.strictEqual(T.pair({ runs: [up("u1", 6 * H), down("d1", 6 * H + 10 * M, { suspended: true })] }).next.size, 0);
}

// A train set to run a later service holds its platform; the next one takes the other platform it may turn back at
{
	const runs = [up("u1", 6 * H, { terminalPlatforms: [10, 11] }), up("u2", 6 * H + 10 * M, { terminalPlatforms: [10, 11] }), down("d1", 6 * H + 20 * M), down("d2", 6 * H + 40 * M)];
	const result = T.pair({ runs, manual: { u1: "d2" } });
	assert.strictEqual(result.next.get("u1").platform, 10);
	assert.strictEqual(result.next.get("u2").to.id, "d1");
	assert.strictEqual(result.next.get("u2").platform, 11);
	assert.strictEqual(result.next.get("u2").moved, true);
	assert.ok(result.warnings.some(w => w.type === "HOLDS_PLATFORM" && w.from === "u1"), JSON.stringify(result.warnings));
	// Without the other platform, the second train has nowhere to stand
	const single = T.pair({ runs: runs.map(r => Object.assign({}, r, { terminalPlatforms: [] })), manual: { u1: "d2" } });
	assert.ok(single.warnings.some(w => w.type === "NO_FREE_PLATFORM" && w.from === "u2"), JSON.stringify(single.warnings));
	// With a turn back rail beyond it, two minutes round, the train with the long wait waits there instead, and the
	// platform is free for the next one
	const rail = T.pair({ runs: runs.map(r => Object.assign({}, r, { terminalPlatforms: [] })), manual: { u1: "d2" }, turnTimes: { 10: { 10: 2 * M } } });
	assert.strictEqual(rail.next.get("u1").kind, "hold");
	assert.strictEqual(rail.next.get("u2").kind, "same");
	assert.ok(!rail.warnings.some(w => w.type === "NO_FREE_PLATFORM"), JSON.stringify(rail.warnings));
	// The last stop's departure for a train on the rail is its own, as it leaves the platform
	assert.strictEqual(rail.next.get("u1").fromDeparture, 6 * H + 4 * M + 20000);
}

// The rail's in and out legs as measured (round 4): 30 s in, 90 s out of the two minutes round
{
	const runs = [up("u1", 6 * H, { terminalPlatforms: [] }), up("u2", 6 * H + 10 * M), down("d1", 6 * H + 20 * M), down("d2", 6 * H + 40 * M)];
	const rail = T.pair({ runs, manual: { u1: "d2" }, turnTimes: { 10: { 10: 2 * M } }, holdIns: { 10: 30000 } });
	assert.strictEqual(rail.next.get("u1").kind, "hold");
	assert.strictEqual(rail.next.get("u1").railStart, 6 * H + 4 * M + 20000 + 30000);
	assert.strictEqual(rail.next.get("u1").railEnd, 6 * H + 40 * M - 90000);
}

// As many trains wait beyond the platform at once as it has places, turn back rails and stabling blocks (运用补完 step 4):
// two trains with long waits overlapping, the second has nowhere with one place and waits beyond too with two
{
	const runs = [up("u1", 6 * H), up("u2", 6 * H + 10 * M), down("d1", 6 * H + 40 * M), down("d2", 6 * H + 50 * M)];
	const manual = { u1: "d1", u2: "d2" };
	const one = T.pair({ runs, manual, turnTimes: { 10: { 10: 2 * M } } });
	assert.strictEqual(one.next.get("u1").kind, "hold");
	assert.notStrictEqual(one.next.get("u2").kind, "hold");
	assert.ok(one.warnings.some(w => w.type === "NO_FREE_PLATFORM" && w.from === "u2"), JSON.stringify(one.warnings));
	const two = T.pair({ runs, manual, turnTimes: { 10: { 10: 2 * M } }, holdCounts: { 10: 2 } });
	assert.strictEqual(two.next.get("u1").kind, "hold");
	assert.strictEqual(two.next.get("u2").kind, "hold");
	assert.ok(!two.warnings.some(w => w.type === "NO_FREE_PLATFORM"), JSON.stringify(two.warnings));
}

// The network's least time to change ends (round 4): a service arriving 40 s after the train leaves its last stop is taken with
// 30 s, not with 60 s
{
	const runs = [up("u1", 6 * H), down("d1", 6 * H + 4 * M + 20000 + 40000)];
	assert.strictEqual(T.pair({ runs }).next.get("u1").to.id, "d1");
	assert.strictEqual(T.pair({ runs, minTurn: 60000 }).next.size, 0);
}

// A loop going round again (2026-11-09): no time to change ends, as its train only stands at the platform and goes on;
// a line turning back at the same platform still needs it
{
	const lap = (id, start) => run(id, [20, 21, 22, 20], [30, 31, 32, 30], start);
	const loop = T.pair({ runs: [lap("l1", 6 * H), lap("l2", 6 * H + 6 * M + 30000)] });
	assert.strictEqual(loop.next.get("l1").to.id, "l2");
	assert.strictEqual(loop.next.get("l1").loop, true);
	const back = T.pair({ runs: [run("u1", [40, 41, 42], [50, 51, 52], 6 * H), run("d1", [42, 41, 40], [52, 51, 50], 6 * H + 4 * M + 30000)] });
	assert.strictEqual(back.next.size, 0, "30 s needed to change ends, 10 s had");
	// Marked circular, a loop even where it is laid out to end at another platform of the station it starts from
	const marked = (id, start) => run(id, [60, 61, 62, 60], [70, 71, 72, 70], start, { circular: true });
	assert.strictEqual(T.pair({ runs: [marked("c1", 6 * H), marked("c2", 6 * H + 6 * M + 30000)] }).next.get("c1").loop, true);
}

console.log("terminal.js: all checks passed");
