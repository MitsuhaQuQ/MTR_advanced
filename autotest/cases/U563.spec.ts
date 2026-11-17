// U563 信号机绑定防护分区（高，0.99.0）: a signal put down binds to the node it protects (the nearest ahead within 8
// blocks with a rail leaving the way it faces), kept in its block entity; the brush binds it again and says to what;
// sneaking with the brush and then clicking a node binds it by hand, without opening the node's screen; looked at, the
// section it protects is drawn (screenshot for judging, AI 判).
// Basic fixture: the straight line along z = 0 at the fixture height, nodes at x = -15 and 15 among others.
// Not covered: a signal at a station throat turnout showing stop until a route is locked (the basic fixture runs no
// trains by duties), a signal from before 0.99.0 keeping the nearest node.
import {test, expect} from "../lib/test";
import {chatSince, client, closeAll, giveOnly, player, screenshot, serverCommand, sleep, teleport, waitFor, waitScreen, FIXTURE_Y} from "../lib/game";

const Y = FIXTURE_Y;
const [SX, SZ] = [-20, 2];

const blockData = async (x: number, y: number, z: number) => (await serverCommand(`data get block ${x} ${y} ${z}`)).output.join(" ");

test("U563 放信号机自动绑定、刷子重绑、手动绑定 @U563", async ({marks}) => {
	const name = await player();
	await serverCommand(`gamemode creative ${name}`);
	await serverCommand(`setblock ${SX} ${Y} ${SZ} minecraft:air`);
	// Put down facing east (the player looking east), beside the track
	await giveOnly("mtr:signal_light_1");
	await serverCommand(`tp ${name} ${SX - 3} ${Y} ${SZ} -90 20`);
	await sleep(500);
	await client.call("/use", {x: SX, y: Y - 1, z: SZ, face: "up"});
	await waitFor("the signal put down", async () => (await serverCommand(`execute if block ${SX} ${Y} ${SZ} mtr:signal_light_1`)).result > 0 || null, 5000);
	const placed = await waitFor("the signal bound", async () => {
		const data = await blockData(SX, Y, SZ);
		return /signal_binding: 1b/.test(data) ? data : null;
	}, 5000).catch(async e => { throw new Error(`${e}; block data: ${await blockData(SX, Y, SZ)}`); });
	expect(placed, "a node for the side it faces").toMatch(/signal_node_0: -?\d+L/);

	// The brush binds it again and says to which node: the one at x = -15, ahead to the east
	await giveOnly("mtr:brush");
	await client.call("/use", {x: SX, y: Y, z: SZ, face: "west"});
	const bound = await waitFor("the bound message", async () => (await chatSince(marks.chat)).messages.find(m => /信号机已绑定到节点/.test(m.text)));
	expect(bound.text).toContain(`-15, ${Y}, 0`);

	// Looked at: the section it protects drawn
	await serverCommand(`tp ${name} ${SX - 2.5} ${Y + 1} ${SZ + 0.5} -90 25`);
	await sleep(2500);
	await screenshot("U563-highlight");

	// By hand: sneaking with the brush on the signal, then the brush on the node at x = 15
	await serverCommand(`tp ${name} ${SX - 3} ${Y} ${SZ} -90 20`);
	await sleep(300);
	await client.call("/use", {x: SX, y: Y, z: SZ, face: "west", sneak: true});
	await waitFor("asked for the node", async () => (await chatSince(marks.chat)).messages.find(m => /手动绑定：60 秒内/.test(m.text)));
	await teleport(15, Y + 1, 3);
	await client.call("/use", {x: 15, y: Y, z: 0, face: "up"});
	const manual = await waitFor("bound by hand", async () => (await chatSince(marks.chat)).messages.find(m => /信号机已手动绑定到节点/.test(m.text)));
	expect(manual.text).toContain(`15, ${Y}, 0`);
	await sleep(500);
	expect(await waitScreen(null, 2000).then(() => true), "the node's screen not opened").toBe(true);
	expect(await blockData(SX, Y, SZ), "kept as set by hand").toMatch(/signal_manual_0: 1b/);

	await closeAll();
	await serverCommand(`setblock ${SX} ${Y} ${SZ} minecraft:air`);
});
