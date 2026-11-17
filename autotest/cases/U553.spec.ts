// U553 缓和曲线、站台随节点旋转（高，0.95.0）, U554 弯曲站台画得平滑（高，0.95.0）, U550 曲线屏蔽门沿连续曲线, U557 曲线屏蔽门：
// 门不碎、缓和曲线上不断开（高，0.96.0）, U556 整侧铺设的站台边可以撤销（中，0.96.0）.
// U553: the fixture's platform A (x -90…-60, z 0) turns with the node at its east end, given a transition curve there;
// the siding's node can't be turned (its length would change); the node is put back afterwards.
// U554/U557/U556: a platform of a length met in play (100 blocks, x -100…0 at z 50, laid apart from the fixture) made an
// arc by turning its two nodes 12° each way, with 10-block transition curves at both ends. Its edge is laid by sneaking
// on one side and a row of curved screen doors put on it, a door pair in the middle; each column's frame is recorded and
// screenshots kept for judging by eye (AI 判): the edge and the doors follow the track smoothly. The other side's edge is
// laid and undone with /mtr undoedge (what the chat's [撤销] runs).
// Not covered: grades over the platform limit (the world is flat), a train stopping at the curved platform.
import {test, expect} from "../lib/test";
import {chat, chatSince, click, client, closeAll, giveOnly, player, screenshot, server, serverCommand, serverData, sleep, teleport, type, waitFor, waitScreen, widget, fixture, FIXTURE_Y} from "../lib/game";

const Y = FIXTURE_Y;
const EAST = -60;
const SIDING_NODE = -110;
/** The long curved platform: its two nodes */
const LONG_Z = 50;
const LONG_WEST = -100;
const LONG_EAST = 0;
const TURN = 12;

type NodeRail = {to: number[], type: string, transitionStart: number, transitionEnd: number, length: number, points: number[][], cantStart: number, cantEnd: number, cantSpeedStart: number, cantSpeedEnd: number, rollMiddle: number};
const node = (x: number, z = 0): Promise<{rails: NodeRail[]}> => server.call("/node", {x, y: Y, z});

/** Opens the node's angle screen with the brush and returns the angle shown */
async function openNode(x: number, z = 0): Promise<number> {
	await closeAll();
	await giveOnly("mtr:brush");
	await teleport(x, Y + 1, z + 4);
	await client.call("/use", {x, y: Y, z, face: "up"});
	await waitScreen("NodeAngleScreen");
	return Number((await widget("NodeAngleScreen.textFieldAngle")).value);
}

async function setNode(angle: number, transition: string) {
	await type("NodeAngleScreen.textFieldAngle", String(angle), true);
	await type("NodeAngleScreen.textFieldTransition", transition, true);
	await click("NodeAngleScreen.buttonSet");
	await waitScreen(null);
}

const isBlock = async (x: number, y: number, z: number, block: string) => (await serverCommand(`execute if block ${x} ${y} ${z} ${block}`)).result > 0;

/** The long platform's rail, read from its west node */
const longRail = async () => (await node(LONG_WEST, LONG_Z)).rails.find(r => r.to[0] === LONG_EAST)!;

/** Where the long platform's track runs at x */
const trackZOf = (rail: NodeRail, x: number) => rail.points.reduce((best, point) => Math.abs(point[0] - x) < Math.abs(best[0] - x) ? point : best)[2];

/** The places along the long platform with an edge block, on one side (z larger than the track's, or smaller) */
async function edgeBlocks(side: 1 | -1): Promise<number[][]> {
	const rail = await longRail();
	const found: number[][] = [];
	for (let x = LONG_WEST - 2; x <= LONG_EAST + 2; x++) {
		const track = trackZOf(rail, x);
		for (let d = 1; d <= 6; d++) {
			const z = Math.floor(track + d * side);
			if (await isBlock(x, Y, z, "mtr:platform_edge")) {
				found.push([x, Y, z]);
			}
		}
	}
	return found;
}

/** The items lying around the long platform (dropped by blocks broken while placing) */
const dropped = async () => (await serverCommand(`execute as @e[type=item,x=-50,y=${Y},z=${LONG_Z},distance=..70] run data get entity @s Item`)).output.join(" | ");

let originalAngle = 0;

// Not serial: a failed check in one doesn't skip the rest (they run in file order, one worker)
test.describe("U553 U554 弯曲站台", () => {
	test("U553 站台节点转动、设缓和曲线；存车线节点转不动 @U553", async ({marks}) => {
		originalAngle = await openNode(EAST);
		const before = await node(EAST);
		await setNode(originalAngle + 30, "4");
		const after = await waitFor("the rails at the node turned and given transition curves", async () => {
			const current = await node(EAST);
			return current.rails.length > 0 && current.rails.every(rail => rail.transitionStart === 4) ? current : null;
		});
		const lengthOf = (rails: NodeRail[], toX: number) => rails.find(rail => rail.to[0] === toX)?.length;
		expect(lengthOf(after.rails, -90), "the platform rail is a curve now, longer than straight").toBeGreaterThan(lengthOf(before.rails, -90)!);
		expect(after.rails.find(rail => rail.to[0] === -90)?.type, "still a platform").toBe("PLATFORM");
		// The platform turned with its node, so it is kept (the same id)
		const snapshot = await serverData();
		expect(JSON.stringify(snapshot.platforms), "platform A kept").toContain(String(fixture.platformA));

		// The siding's node: its length would change, so the change is refused and said in the chat
		const sidingBefore = await node(SIDING_NODE);
		const sidingAngle = await openNode(SIDING_NODE);
		await setNode(sidingAngle + 15, "0");
		const refused = await waitFor("the refusal in the chat", async () => (await chatSince(marks.chat)).messages.find(m => /存车线长度会变/.test(m.text)));
		expect(refused.text).toContain("无法修改");
		await sleep(500);
		expect((await node(SIDING_NODE)).rails, "the siding's rails as they were").toEqual(sidingBefore.rails);
		expect(await openNode(SIDING_NODE), "the node's angle as it was").toBe(sidingAngle);

		// Platform A back as it was
		await openNode(EAST);
		await setNode(originalAngle, "0");
		await waitFor("the node back as it was", async () => (await node(EAST)).rails.every(rail => rail.transitionStart === 0) || null);
		await closeAll();
	});

	test("U554 弯曲站台边和曲线屏蔽门：截图看是否平滑 @U554 @U550 @U557 @U558", async ({marks}, testInfo) => {
		test.setTimeout(600_000);
		const name = await player();
		await serverCommand(`gamemode creative ${name}`);

		// A 100-block platform, made an arc: the west node turned one way, the east node the other, transition curves at both
		await server.call("/connect", {from: [LONG_WEST, Y, LONG_Z], to: [LONG_EAST, Y, LONG_Z], type: "PLATFORM"});
		const westAngle = await openNode(LONG_WEST, LONG_Z);
		await setNode(westAngle + TURN, "10");
		const eastAngle = await openNode(LONG_EAST, LONG_Z);
		await setNode(eastAngle - TURN, "10");
		const rail = await waitFor("the long platform curved, with transition curves at both ends", async () => {
			const current = await longRail();
			return current && current.transitionStart === 10 && current.transitionEnd === 10 ? current : null;
		});
		const middleZ = trackZOf(rail, -50);
		const endZ = trackZOf(rail, LONG_EAST);
		await testInfo.attach("long platform", {body: JSON.stringify({length: rail.length, middleZ, endZ})});
		expect(Math.abs(middleZ - endZ), "an arc: the middle stands off the line between the ends").toBeGreaterThan(2);
		await closeAll();

		// The edge on the side with z larger than the track's, laid by sneaking against the ground beside the platform
		await giveOnly("mtr:platform_edge 64");
		await teleport(-50, Y + 1, Math.round(middleZ) + 6);
		await client.call("/use", {x: -50, y: Y - 1, z: Math.round(middleZ) + 3, face: "up", sneak: true});
		const laid = await waitFor("the laid message", async () => (await chatSince(marks.chat)).messages.find(m => /铺了 \d+ 块站台边/.test(m.text)));
		expect(laid.text, "the message has the undo button").toContain("[撤销]");
		const edge = await edgeBlocks(1);
		expect(edge.length, "edge blocks along the platform").toBeGreaterThan(100);

		// Curved screen door glass on every edge block the edge line crosses (elsewhere it says the line doesn't pass)
		await giveOnly("mtr:curved_psd_glass 64");
		for (const place of edge) {
			// Within reach: the server takes a click only from a few blocks away
			await serverCommand(`tp ${name} ${place[0] + 0.5} ${Y + 1} ${place[2] + 3.5}`);
			await client.call("/use", {x: place[0], y: place[1], z: place[2], face: "up"});
			await sleep(30);
		}
		await sleep(300);
		const columns: number[][] = [];
		for (const place of edge) {
			if (await isBlock(place[0], Y + 1, place[2], "mtr:curved_psd_glass")) columns.push([place[0], place[2]]);
		}
		expect(columns.length, "screen door columns along the platform").toBeGreaterThan(80);
		// Each column's frame: a run joined all along has frame ends at its two ends only
		const frames: string[] = [];
		let ends = 0;
		for (const [x, z] of columns) {
			let side = "?";
			for (const value of ["single", "left", "right", "middle"]) {
				if (await isBlock(x, Y + 1, z, `mtr:curved_psd_glass[side=${value}]`)) side = value;
			}
			if (side !== "middle") ends++;
			const data = (await serverCommand(`data get block ${x} ${Y + 1} ${z}`)).output.join(" ").replace(/.*entity data: /, "");
			frames.push(`${x},${z} ${side} ${data}`);
		}
		await testInfo.attach("glass frames", {body: frames.join(String.fromCharCode(10))});
		await testInfo.attach("dropped after glass", {body: await dropped()});

		// A door pair needs glass on the next column along the line too: tried from the middle out until one goes in
		await giveOnly("mtr:curved_psd_door");
		const order = columns.map((column, i) => ({column, distance: Math.abs(i - columns.length / 2)})).sort((a, b) => a.distance - b.distance).map(entry => entry.column);
		let door: number[] | undefined;
		for (const column of order) {
			await serverCommand(`tp ${name} ${column[0] + 0.5} ${Y + 1} ${column[1] + 3.5}`);
			await sleep(100);
			await client.call("/use", {x: column[0], y: Y + 1, z: column[1], face: "south"});
			await sleep(150);
			if (await isBlock(column[0], Y + 1, column[1], "mtr:curved_psd_door")) {
				door = column;
				break;
			}
		}
		await testInfo.attach("glass and door columns", {body: JSON.stringify({columns: columns.length, frameEnds: ends, door})});
		await testInfo.attach("dropped after doors", {body: await dropped()});

		// Screenshots, as a spectator (no hand, no falling): along the platform, across the track at it, from above, and
		// close to the edge and the doors from the track side (the side a train sees; the strip's back is whole blocks)
		await giveOnly();
		await serverCommand(`gamemode spectator ${name}`);
		try {
			for (const [label, x, y, z, yaw, pitch] of [
				["along", 6, Y + 2.6, endZ + 3, 90, 12],
				// The platform's east end from above (0.100.0): the edge marking stops square at the end of the edge
				["end-close", LONG_EAST + 2.5, Y + 3.2, endZ + 2.5, 135, 55],
				["across", -50, Y + 4, middleZ - 14, 0, 12],
				["above", -50, Y + 70, middleZ, 0, 90],
				["edge-close", -40, Y + 1.2, trackZOf(rail, -40) - 1, 50, 25],
				["glass-close", -55, Y + 2, trackZOf(rail, -55) - 1.5, -40, 5],
				// The boarding arrows on the edge by the door (0.97.0): from above, and from the platform behind the doors
				["door-platform", (door?.[0] ?? -50) + 0.5, Y + 2.7, (door?.[1] ?? 57) + 3, 180, 50],
				["door-platform-wide", (door?.[0] ?? -50) + 0.5, Y + 2.8, (door?.[1] ?? 57) + 5, 180, 30],
			] as [string, number, number, number, number, number][]) {
				await serverCommand(`tp ${name} ${x} ${y} ${z} ${yaw} ${pitch}`);
				await sleep(3000);
				await screenshot(`U554-${label}`);
			}
		} finally {
			await serverCommand(`gamemode creative ${name}`);
		}
		expect(door, "a door pair put on the curved glass").toBeTruthy();
		expect(ends, "the run of glass joined all along: frame ends at its two ends only").toBeLessThanOrEqual(2);
	});

	test("U556 整侧铺设的站台边可以撤销 @U556", async ({marks}) => {
		const middleZ = trackZOf(await longRail(), -50);
		await giveOnly("mtr:platform_edge 64");
		await teleport(-50, Y + 1, Math.round(middleZ) - 6);
		await client.call("/use", {x: -50, y: Y - 1, z: Math.round(middleZ) - 3, face: "up", sneak: true});
		const laid = await waitFor("the laid message", async () => (await chatSince(marks.chat)).messages.find(m => /铺了 \d+ 块站台边/.test(m.text)));
		const count = Number(/铺了 (\d+) 块/.exec(laid.text)![1]);
		expect((await edgeBlocks(-1)).length).toBe(count);
		const kept = (await edgeBlocks(1)).length;

		await chat("/mtr undoedge");
		const undone = await waitFor("the undone message", async () => (await chatSince(marks.chat)).messages.find(m => /撤回了 \d+ 块站台边/.test(m.text)));
		expect(undone.text).toContain(`撤回了 ${count} 块`);
		expect((await edgeBlocks(-1)).length, "the other side's edge taken back").toBe(0);
		expect((await edgeBlocks(1)).length, "the edge laid before, left alone").toBe(kept);

		await chat("/mtr undoedge");
		await waitFor("nothing more to undo", async () => (await chatSince(marks.chat)).messages.find(m => /没有可以撤销/.test(m.text)));
	});

	test("U561 节点设外轨超高：弯道上轨道侧倾、外轨高 @U561", async ({}, testInfo) => {
		test.setTimeout(300_000);
		const name = await player();
		// 150 mm on both nodes of the long curved platform
		for (const x of [LONG_WEST, LONG_EAST]) {
			await openNode(x, LONG_Z);
			await type("NodeAngleScreen.textFieldCant", "150", true);
			await click("NodeAngleScreen.buttonSet");
			await waitScreen(null);
		}
		const rail = await waitFor("the long platform given its superelevation", async () => {
			const current = await longRail();
			return current && current.cantStart === 150 && current.cantEnd === 150 ? current : null;
		});
		await testInfo.attach("cant", {body: JSON.stringify({rollMiddle: rail.rollMiddle, degrees: rail.rollMiddle * 180 / Math.PI})});
		// The whole cant halfway along the arc: asin(150 / 1500), about 5.7°
		expect(Math.abs(rail.rollMiddle)).toBeGreaterThan(0.09);
		expect(Math.abs(rail.rollMiddle)).toBeLessThan(0.11);

		// Screenshots, as a spectator: low along the track from its east end, and across it from the inside of the curve
		await giveOnly();
		await serverCommand(`gamemode spectator ${name}`);
		try {
			const middleZ = trackZOf(rail, -50);
			for (const [label, x, y, z, yaw, pitch] of [
				["cant-along", -20, Y + 1.2, trackZOf(rail, -20), 90, 8],
				["cant-across", -50, Y + 1.5, middleZ - 8, 0, 10],
				// Level with the rails, across them from the inside of the curve: the far rail (the outer one) higher
				["cant-low", -50, Y - 0.35, middleZ - 2.6, 0, 0],
			] as [string, number, number, number, number, number][]) {
				await serverCommand(`tp ${name} ${x} ${y} ${z} ${yaw} ${pitch}`);
				await sleep(3000);
				await screenshot(`U561-${label}`);
			}
		} finally {
			await serverCommand(`gamemode creative ${name}`);
		}
	});

	test("U561 超高按角度输入：切到度、填 4.6°、记住单位 @U561", async () => {
		// The west node in degrees: 4.6° is about 120.3 mm
		await openNode(LONG_WEST, LONG_Z);
		expect((await widget("NodeAngleScreen.buttonCantUnit")).text, "mm by default, as the 150 mm set before").toBe("mm");
		await click("NodeAngleScreen.buttonCantUnit");
		expect((await widget("NodeAngleScreen.buttonCantUnit")).text).toBe("°");
		expect((await widget("NodeAngleScreen.textFieldCant")).value, "150 mm shown as its angle").toBe("5.74");
		await type("NodeAngleScreen.textFieldCant", "4.6", true);
		await click("NodeAngleScreen.buttonSet");
		await waitScreen(null);
		const rail = await waitFor("the cant from the angle", async () => {
			const current = await longRail();
			return current && Math.abs(current.cantStart - 120.3) < 0.2 ? current : null;
		});
		expect(rail.cantEnd, "the other end as before").toBe(150);
		// Opened again: still in degrees
		await openNode(LONG_WEST, LONG_Z);
		expect((await widget("NodeAngleScreen.buttonCantUnit")).text).toBe("°");
		expect((await widget("NodeAngleScreen.textFieldCant")).value).toBe("4.60");
		await closeAll();
	});
});
