// U568 方块实体存档可靠性（高，0.100.0）: what is left of a free escalator never stays in part, and bad data in a block
// entity never crashes the game.
// ① A free escalator block whose first block is not there (left over from a crash between chunks being saved) goes
//    by itself when it is loaded.
// ② An escalator taken away is put on the dimension's list of those taken away (saved with the world): a block of it
//    loaded later goes by itself, even with its first block far away and not loaded (no chunk is loaded for it).
// ③ Bad data given to a free escalator's first block (width 7, speed 99, mode 9, a broken point) is read as the
//    defaults; and a curved screen door with an angle and cut out of range and a curve that isn't one is drawn as
//    nothing curved: the server and the client keep running (screenshot for judging, AI 判: no crash, nothing odd drawn).
// Basic fixture, away from the track (z = 70 and on).
// Not covered: an actual crash between chunk saves; numbers that aren't (NaN) — SNBT can't write them; the unit
// tests read them.
import {test, expect} from "../lib/test";
import {existsSync} from "fs";
import {client, giveOnly, player, screenshot, serverCommand, sleep, waitFor, FIXTURE_Y} from "../lib/game";

const Y = FIXTURE_Y;
const Z = 70;
const LOW = Y - 1;

/** A block position packed as the game packs it (BlockPos.asLong) */
function packed(x: number, y: number, z: number): string {
	const value = ((BigInt(x) & 0x3FFFFFFn) << 38n) | ((BigInt(z) & 0x3FFFFFFn) << 12n) | (BigInt(y) & 0xFFFn);
	return BigInt.asIntN(64, value).toString();
}

const isEscalator = async (x: number, y: number, z: number) =>
	(await serverCommand(`execute if block ${x} ${y} ${z} mtr:free_escalator`)).result > 0;
const FLAT = "[B;2b,2b,2b,2b,2b,2b,2b,2b,2b,2b,2b,2b,2b,2b,2b,2b]";

test("U568 自由扶梯残块自清、拆除名单、坏数据不崩 @U568", async () => {
	const name = await player();
	await serverCommand(`gamemode creative ${name}`);
	await serverCommand(`fill 30 ${LOW} ${Z - 3} 60 ${Y + 6} ${Z + 10} minecraft:air`);
	await serverCommand(`fill 30 ${LOW} ${Z - 3} 60 ${LOW} ${Z + 10} minecraft:smooth_stone`);

	// ① No first block where it says
	await serverCommand(`setblock 40 ${LOW + 1} ${Z} mtr:free_escalator{free_escalator_id:777L,free_escalator_head:${packed(41, LOW + 1, Z)}L,free_escalator_heights:${FLAT}}`);
	await waitFor("the left-over block gone", async () => !(await isEscalator(40, LOW + 1, Z)), 3000);

	// A walkway, built as a player would, then taken away
	await giveOnly("mtr:free_escalator");
	for (const x of [34, 44]) {
		await serverCommand(`tp ${name} ${x - 1.5} ${LOW + 1} ${Z + 2.5} -90 40`);
		await sleep(250);
		await client.call("/use", {x, y: LOW, z: Z, face: "up"});
		await sleep(200);
	}
	await client.call("/use", {x: 44, y: LOW, z: Z, face: "up", sneak: true});
	await waitFor("built", async () => await isEscalator(38, LOW + 1, Z) || null, 5000);
	const idText = (await serverCommand(`data get block 38 ${LOW + 1} ${Z} free_escalator_id`)).output.join(" ");
	const id = (idText.match(/(-?\d+)L?\s*$/) || [])[1];
	expect(id, `its id (${idText})`).toBeTruthy();
	await serverCommand(`setblock 39 ${LOW + 1} ${Z} minecraft:air`);
	await sleep(300);
	expect(await isEscalator(34, LOW + 1, Z), "all of it gone").toBe(false);

	// ② A block of it coming back (as from a chunk saved before it was taken away), its first block far off, not loaded
	await serverCommand(`setblock 50 ${LOW + 1} ${Z + 4} mtr:free_escalator{free_escalator_id:${id}L,free_escalator_head:${packed(100000, LOW + 1, Z)}L,free_escalator_heights:${FLAT}}`);
	await waitFor("the block of an escalator taken away gone", async () => !(await isEscalator(50, LOW + 1, Z + 4)), 3000);
	// The list is saved with the world
	await serverCommand("save-all flush");
	await sleep(500);
	expect(existsSync("C:/gtmp/ui/server/world/data/mtr_free_escalator_removals.dat"), "the list saved").toBe(true);

	// ③ Bad data: read as the defaults, nothing crashes
	await serverCommand(`setblock 36 ${LOW + 1} ${Z + 6} mtr:free_escalator{free_escalator_id:0L,free_escalator_head:${packed(36, LOW + 1, Z + 6)}L,free_escalator_heights:[B;99b,-5b,2b],free_escalator_points:[L;1L,2L],free_escalator_width:7.0d,free_escalator_speed:99.0d,free_escalator_mode:9,free_escalator_cells:[L;5L]}`);
	const bad = (await serverCommand(`data get block 36 ${LOW + 1} ${Z + 6}`)).output.join(" ");
	expect(bad, "width read as 1").toMatch(/free_escalator_width: 1\.0d/);
	expect(bad, "speed read as 0.5").toMatch(/free_escalator_speed: 0\.5d/);
	expect(bad, "mode read as stopped").toMatch(/free_escalator_mode: 0/);
	await serverCommand(`setblock 38 ${LOW + 1} ${Z + 6} mtr:curved_psd_glass{bend_angle:999,bend_cut:999,curve_from:1L,curve_to:2L,curve_offset:1.0E300d,curve_offset_end:-1.0E300d,curve_side:7,curve_start:1.0E300d,curve_end:-1.0E300d}`);
	await serverCommand(`tp ${name} 37.5 ${LOW + 1} ${Z + 3.5} 0 20`);
	await sleep(2000);
	await screenshot("U568-bad-data");
	expect((await serverCommand("time query gametime")).result, "the server still running").toBeGreaterThan(0);
	expect(await client.call("/state"), "the client still running").toBeTruthy();

	await serverCommand(`fill 30 ${LOW + 1} ${Z - 3} 60 ${Y + 6} ${Z + 10} minecraft:air`);
});
