// U566 原版扶梯会动的立体台阶（中，0.100.0）: the original escalator, put down piece by piece with its item as a player
// would (flat, up four blocks, flat), keeps how it ran (orientations, the brush cycling up / down / stop), has no block
// entity on its steps (nothing added to saves), and has its steps drawn moving by the client. Screenshots a moment apart, running up and running down, and
// stopped (for judging, AI 判: steps drawn as solid steps, moving between the two shots, none floating or missing at
// the bends).
// Basic fixture, beside the track (z = 20 and 21).
// Not covered: steps in a world from before 0.100.0 (found the same way, nothing to convert).
import {test, expect} from "../lib/test";
import {giveOnly, player, screenshot, serverCommand, sleep, client, FIXTURE_Y} from "../lib/game";

const Y = FIXTURE_Y;
const X0 = 30, Z = 20;
// Heights of the escalator's pieces from x = X0 eastwards: flat, up four, flat
const HEIGHTS = [0, 0, 0, 1, 2, 3, 4, 4, 4];
const ORIENTATIONS = ["landing_bottom", "flat", "transition_bottom", "slope", "slope", "slope", "transition_top", "flat", "landing_top"];

const stepAt = async (x: number, y: number, z: number, state = "") =>
	(await serverCommand(`execute if block ${x} ${y} ${z} mtr:escalator_step${state}`)).result > 0;

test("U566 原版扶梯立体台阶、方向切换照旧 @U566", async () => {
	const name = await player();
	await serverCommand(`gamemode creative ${name}`);
	await serverCommand(`fill ${X0 - 4} ${Y - 1} ${Z - 2} ${X0 + 12} ${Y + 10} ${Z + 3} minecraft:air`);
	for (let i = 0; i < HEIGHTS.length; i++) {
		await serverCommand(`fill ${X0 + i} ${Y - 1 + HEIGHTS[i]} ${Z} ${X0 + i} ${Y - 1 + HEIGHTS[i]} ${Z + 1} minecraft:stone`);
	}
	// Put down from the bottom, looking east (the left half at z = Z, the right one south of it)
	await giveOnly("mtr:escalator");
	for (let i = 0; i < HEIGHTS.length; i++) {
		const y = Y + HEIGHTS[i];
		await serverCommand(`tp ${name} ${X0 + i - 2.5} ${y + 1} ${Z + 0.5} -90 30`);
		await sleep(250);
		await client.call("/use", {x: X0 + i, y: y - 1, z: Z, face: "up"});
		await sleep(150);
		expect(await stepAt(X0 + i, y, Z), `step ${i} put down`).toBe(true);
	}
	await sleep(500);
	for (let i = 0; i < HEIGHTS.length; i++) {
		expect(await stepAt(X0 + i, Y + HEIGHTS[i], Z, `[orientation=${ORIENTATIONS[i]}]`), `piece ${i} is ${ORIENTATIONS[i]}`).toBe(true);
		expect(await stepAt(X0 + i, Y + HEIGHTS[i], Z + 1, `[orientation=${ORIENTATIONS[i]},side=right]`), `its right half`).toBe(true);
		// No block entity: the steps are drawn by the client from where it finds them, nothing is added to saves
		expect((await serverCommand(`data get block ${X0 + i} ${Y + HEIGHTS[i]} ${Z}`)).result, `piece ${i} has no block entity`).toBe(0);
	}

	// The brush cycles it as before: stopped -> up -> down -> stopped
	await giveOnly("mtr:brush");
	const cycle = async (state: string) => {
		await serverCommand(`tp ${name} ${X0 + 4.5} ${Y + 6} ${Z - 3} 30 45`);
		await sleep(300);
		await client.call("/use", {x: X0 + 4, y: Y + 2, z: Z, face: "up"});
		await sleep(400);
		expect(await stepAt(X0 + 1, Y, Z, state), `whole escalator ${state}`).toBe(true);
		expect(await stepAt(X0 + 7, Y + 4, Z + 1, state), `whole escalator ${state}, right half`).toBe(true);
	};
	await serverCommand(`tp ${name} ${X0 + 4.5} ${Y + 6} ${Z - 3} 30 45`);
	await sleep(300);
	// From however it was put down, round to stopped, then up
	for (let i = 0; i < 3 && !(await stepAt(X0 + 4, Y + 2, Z, "[status=false]")); i++) {
		await client.call("/use", {x: X0 + 4, y: Y + 2, z: Z, face: "up"});
		await sleep(400);
	}
	expect(await stepAt(X0 + 1, Y, Z, "[status=false]"), "stopped").toBe(true);
	await cycle("[direction=true,status=true]");

	// Looked at along the run, from above the bottom landing and from above the top one: up, a moment later, then down
	// and stopped
	// (standing on barriers, which are not drawn, so as not to fall)
	await serverCommand(`fill ${X0 - 3} ${Y + 1} ${Z} ${X0 - 3} ${Y + 1} ${Z + 1} minecraft:barrier`);
	await serverCommand(`fill ${X0 + 11} ${Y + 5} ${Z} ${X0 + 11} ${Y + 5} ${Z + 1} minecraft:barrier`);
	const fromBottom = `tp ${name} ${X0 - 2.5} ${Y + 2} ${Z + 1} -90 28`;
	const fromTop = `tp ${name} ${X0 + 11.5} ${Y + 6} ${Z + 1} 90 35`;
	await serverCommand(fromBottom);
	await sleep(2500);
	await screenshot("U566-up-1");
	await sleep(250);
	await screenshot("U566-up-2");
	await serverCommand(fromTop);
	await sleep(1500);
	await screenshot("U566-up-top");
	await cycle("[direction=false,status=true]");
	await serverCommand(fromBottom);
	await sleep(1500);
	await screenshot("U566-down");
	await cycle("[status=false]");
	await serverCommand(fromBottom);
	await sleep(1500);
	await screenshot("U566-stopped");

	await serverCommand(`fill ${X0 - 4} ${Y - 1} ${Z - 2} ${X0 + 12} ${Y + 10} ${Z + 3} minecraft:air`);
});
