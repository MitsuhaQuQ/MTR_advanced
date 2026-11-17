// U567 自由扶梯：标点生成、运送、刷子与设置、拆一块全拆（高，0.100.0）: points marked in order with the free escalator
// item (lower floor, foot of the slope, top of the slope, upper floor: 4 up over 8, 1 in 2, 26.57°), built by sneaking; the
// blocks under its surface give the collision; a mob and the player standing on it are carried up; the brush cycles up /
// down / stop; sneaking with the brush gives the settings; the width set to 2 builds it again wider; taking one block away
// takes the whole escalator away. A moving walkway marked at an angle (not along x or z). Screenshots for judging
// (AI 判): steps level on the slope, bending smoothly at both ends, balustrades and handrails along both sides, comb
// plates at the ends, the walkway's pallets lying flat.
// Basic fixture, away from the track (z = 40 and on).
// Not covered: survival (the item is not used up, nothing drops), a point off the vertical plane or too steep (unit
// tests), riding down.
import {test, expect} from "../lib/test";
import {chatSince, client, giveOnly, player, screenshot, serverCommand, sleep, waitFor, FIXTURE_Y} from "../lib/game";

const Y = FIXTURE_Y;
const Z = 40;
// Lower floor x 56..63 at Y - 1, upper floor x 71..78 at Y + 4
const LOW = Y - 1, HIGH = Y + 3;
const POINTS: [number, number][] = [[58, LOW], [63, LOW], [71, HIGH], [76, HIGH]];

const isEscalator = async (x: number, y: number, z: number) =>
	(await serverCommand(`execute if block ${x} ${y} ${z} mtr:free_escalator`)).result > 0;
const entityX = async (selector: string) => {
	const {result, output} = await serverCommand(`execute as ${selector} run data get entity @s Pos[0] 1000`);
	if (!output.length && !result) throw new Error(`no position for ${selector}`);
	const match = output.join(" ").match(/(-?\d+)\s*$/);
	return (match ? parseInt(match[1], 10) : result) / 1000;
};
test("U567 自由扶梯标点生成、运送、刷子与设置、拆除 @U567", async ({marks}) => {
	const name = await player();
	await serverCommand(`gamemode creative ${name}`);
	await serverCommand(`kill @e[type=minecraft:iron_golem,tag=u567]`);
	await serverCommand(`fill 50 ${LOW - 1} ${Z - 4} 92 ${HIGH + 6} ${Z + 14} minecraft:air`);
	await serverCommand(`fill 56 ${LOW} ${Z - 2} 63 ${LOW} ${Z + 3} minecraft:smooth_stone`);
	await serverCommand(`fill 71 ${HIGH} ${Z - 2} 78 ${HIGH} ${Z + 3} minecraft:smooth_stone`);
	await serverCommand(`fill 64 ${LOW - 1} ${Z - 2} 70 ${LOW - 1} ${Z + 3} minecraft:smooth_stone`);

	// Mark the four points, then build sneaking
	await giveOnly("mtr:free_escalator");
	for (const [x, y] of POINTS) {
		await serverCommand(`tp ${name} ${x - 1.5} ${y + 1} ${Z + 2.5} -90 40`);
		await sleep(250);
		await client.call("/use", {x, y, z: Z, face: "up"});
		await sleep(200);
	}
	const marked = await chatSince(marks.chat);
	expect(marked.messages.filter(m => /已标记第 \d 点/.test(m.text)).length, "four points marked").toBe(4);
	expect(marked.messages.some(m => /坡度 27°/.test(m.text)), "the slope said (1 in 2, 26.57°)").toBe(true);
	await client.call("/use", {x: 76, y: HIGH, z: Z, face: "up", sneak: true});
	await waitFor("built", async () => (await chatSince(marks.chat)).messages.find(m => /自由扶梯已生成/.test(m.text)), 5000);
	expect(await isEscalator(58, LOW + 1, Z), "over the lower floor").toBe(true);
	expect(await isEscalator(76, HIGH + 1, Z), "over the upper floor").toBe(true);
	expect(await isEscalator(67, Y + 2, Z) || await isEscalator(67, Y + 1, Z), "on the slope").toBe(true);
	const head = (await serverCommand(`data get block 58 ${LOW + 1} ${Z}`)).output.join(" ");
	expect(head, "the first block holds the escalator").toMatch(/free_escalator_cells/);

	// Looked at from the bottom and from the top
	await serverCommand(`fill 54 ${LOW + 1} ${Z} 54 ${LOW + 1} ${Z + 1} minecraft:barrier`);
	await serverCommand(`tp ${name} 54.5 ${LOW + 2} ${Z + 0.5} -90 12`);
	await sleep(2500);
	await screenshot("U567-from-bottom");
	await serverCommand(`setblock 66 ${HIGH + 2} ${Z - 5} minecraft:barrier`);
	await serverCommand(`tp ${name} 66.5 ${HIGH + 3} ${Z - 4.5} -20 30`);
	await sleep(1500);
	await screenshot("U567-from-side");

	// A mob standing on it is carried up
	// (not an animal: the test server doesn't spawn animals, and the game takes away any there are)
	await serverCommand(`summon minecraft:iron_golem 60.5 ${LOW + 1.2} ${Z + 0.5} {NoAI:1b,PersistenceRequired:1b,Tags:["u567"]}`);
	await sleep(1000);
	const gameTime = async () => (await serverCommand("time query gametime")).result;
	const golemX0 = await entityX(`@e[type=minecraft:iron_golem,tag=u567,limit=1]`);
	const ticks0 = await gameTime();
	await sleep(5000);
	const golemX1 = await entityX(`@e[type=minecraft:iron_golem,tag=u567,limit=1]`);
	const ticks = await gameTime() - ticks0;
	// 0.5 m/s along the steepest part (1 in 2): 0.5 / 20 / sqrt(1 + 0.5²) = 0.0224 blocks a tick seen from above
	const expected = ticks * 0.5 / 20 / Math.sqrt(1 + 0.5 * 0.5);
	console.log("golem", golemX0, golemX1, "ticks", ticks, "expected", expected);
	expect(golemX1 - golemX0, `the golem carried east (${golemX0} -> ${golemX1} in ${ticks} ticks)`).toBeGreaterThan(expected * 0.8);
	expect(golemX1 - golemX0, `not faster than the steps`).toBeLessThan(expected * 1.2 + 0.1);
	await serverCommand(`kill @e[type=minecraft:iron_golem,tag=u567]`);

	// The player too (their own client moves them); not flying (a player flying is not carried), which an earlier
	// test may have left them doing: survival and back stops it
	await serverCommand(`gamemode survival ${name}`);
	await serverCommand(`gamemode creative ${name}`);
	await serverCommand(`tp ${name} 59.5 ${LOW + 1.2} ${Z + 0.5} -90 0`);
	await sleep(800);
	const playerX0 = await entityX(name);
	await sleep(4000);
	const playerX1 = await entityX(name);
	expect(playerX1 - playerX0, `the player carried east (${playerX0} -> ${playerX1})`).toBeGreaterThan(1);

	// Brush: up -> down -> stopped -> up; sneaking: the settings
	await giveOnly("mtr:brush");
	await serverCommand(`tp ${name} 60.5 ${LOW + 1.2} ${Z + 3.5} 180 45`);
	await sleep(400);
	const mode = async () => ((await serverCommand(`data get block 58 ${LOW + 1} ${Z} free_escalator_mode`)).output.join(" ").match(/(\d+)\s*$/) || [])[1];
	expect(await mode(), "built running up").toBe("1");
	for (const expected of ["2", "0", "1"]) {
		await client.call("/use", {x: 60, y: LOW + 1, z: Z, face: "up"});
		await sleep(400);
		expect(await mode()).toBe(expected);
	}
	await client.call("/use", {x: 60, y: LOW + 1, z: Z, face: "up", sneak: true});
	const settings = await waitFor("the settings", async () => (await chatSince(marks.chat)).messages.find(m => /自由扶梯设置/.test(m.text)), 5000);
	expect(settings.text).toMatch(/0\.65 m\/s/);
	expect(settings.text).toMatch(/\[2 格\]/);

	// Speed and width from the settings' commands
	expect((await serverCommand(`mtr escalator speed 58 ${LOW + 1} ${Z} 0.65`)).result).toBe(1);
	expect((await serverCommand(`data get block 58 ${LOW + 1} ${Z} free_escalator_speed`)).output.join(" ")).toMatch(/0\.65/);
	expect((await serverCommand(`mtr escalator width 58 ${LOW + 1} ${Z} 2`)).result).toBe(1);
	expect(await isEscalator(60, LOW + 1, Z + 1), "wider: a block on the side").toBe(true);
	await serverCommand(`tp ${name} 54.5 ${LOW + 2} ${Z + 0.5} -90 12`);
	await sleep(1500);
	await screenshot("U567-wide");

	// One block taken away: all of it goes
	await serverCommand(`setblock 61 ${LOW + 1} ${Z} minecraft:air`);
	await sleep(300);
	expect(await isEscalator(58, LOW + 1, Z), "the first block gone").toBe(false);
	expect(await isEscalator(76, HIGH + 1, Z), "the far end gone").toBe(false);
	expect(await isEscalator(60, LOW + 1, Z + 1), "the side gone").toBe(false);

	// A walkway at an angle: 6 east and 3 south on the lower floor
	await serverCommand(`fill 50 ${LOW} ${Z + 4} 70 ${LOW} ${Z + 14} minecraft:smooth_stone`);
	await giveOnly("mtr:free_escalator");
	for (const [x, z] of [[52, Z + 6], [60, Z + 10]]) {
		await serverCommand(`tp ${name} ${x - 1.5} ${LOW + 1} ${z + 1.5} -90 40`);
		await sleep(250);
		await client.call("/use", {x, y: LOW, z, face: "up"});
		await sleep(200);
	}
	await client.call("/use", {x: 60, y: LOW, z: Z + 10, face: "up", sneak: true});
	await waitFor("the walkway built", async () => (await chatSince(marks.chat)).messages.find(m => /自动人行道/.test(m.text)), 5000);
	expect(await isEscalator(56, LOW + 1, Z + 8), "the walkway's middle").toBe(true);
	await serverCommand(`tp ${name} 49.5 ${LOW + 3} ${Z + 4.5} -60 25`);
	await sleep(2000);
	await screenshot("U567-walkway");

	await serverCommand(`setblock 56 ${LOW + 1} ${Z + 8} minecraft:air`);
	await serverCommand(`fill 50 ${LOW - 1} ${Z - 4} 92 ${HIGH + 6} ${Z + 14} minecraft:air`);
});
