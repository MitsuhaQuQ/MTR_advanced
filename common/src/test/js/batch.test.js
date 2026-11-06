// Checks the web editor's timetable batch editing (assets/mtr/editor/batch.js): node common/src/test/js/batch.test.js
const assert = require("assert");
const path = require("path");
const batch = require(path.join(__dirname, "../../main/resources/assets/mtr/editor/batch.js"));
const M = 60000, H = 60 * M;

// Both ends included, evenly apart
assert.deepStrictEqual(batch.evenTimes(8 * H, 9 * H, 5), [8 * H, 8 * H + 15 * M, 8 * H + 30 * M, 8 * H + 45 * M, 9 * H]);
assert.deepStrictEqual(batch.evenTimes(8 * H, 9 * H, 1), [8 * H]);
assert.deepStrictEqual(batch.evenTimes(8 * H, 9 * H, 0), []);
// Past midnight
assert.deepStrictEqual(batch.evenTimes(23 * H, 1 * H, 3), [23 * H, 0, 1 * H]);

// From the start every step until the end, the end included where one falls on it
assert.deepStrictEqual(batch.stepTimes(5 * H, 6 * H, 20 * M), [5 * H, 5 * H + 20 * M, 5 * H + 40 * M, 6 * H]);
assert.deepStrictEqual(batch.stepTimes(5 * H, 6 * H, 25 * M), [5 * H, 5 * H + 25 * M, 5 * H + 50 * M]);
assert.deepStrictEqual(batch.stepTimes(23 * H, 1 * H, H), [23 * H, 0, 1 * H]);
assert.deepStrictEqual(batch.stepTimes(5 * H, 6 * H, 0), []);
assert.strictEqual(batch.stepTimes(5 * H, 23 * H, 5 * M).length, 18 * 12 + 1);

// Spaced again from the first
assert.deepStrictEqual(batch.respace([8 * H, 8 * H + 3 * M, 8 * H + 11 * M], 10 * M), [8 * H, 8 * H + 10 * M, 8 * H + 20 * M]);

// Beside a service: halfway to the neighbour, or as far beyond as the other neighbour
assert.strictEqual(batch.besideTime(8 * H, -1, 7 * H + 50 * M, 8 * H + 10 * M), 7 * H + 55 * M);
assert.strictEqual(batch.besideTime(8 * H, 1, 7 * H + 50 * M, 8 * H + 10 * M), 8 * H + 5 * M);
assert.strictEqual(batch.besideTime(8 * H, -1, null, 8 * H + 10 * M), 7 * H + 50 * M);
assert.strictEqual(batch.besideTime(8 * H, 1, 7 * H + 50 * M, null), 8 * H + 10 * M);
assert.strictEqual(batch.besideTime(8 * H, 1, null, null), 8 * H + 10 * M);

// A run with nothing skipped, either way round
assert.deepStrictEqual(batch.runBetween(["a", "b", "c", "d", "e"], "b", "d"), ["b", "c", "d"]);
assert.deepStrictEqual(batch.runBetween(["a", "b", "c", "d", "e"], "d", "b"), ["b", "c", "d"]);
assert.deepStrictEqual(batch.runBetween(["a", "b"], "a", "x"), []);

console.log("batch.test.js: all passed");
