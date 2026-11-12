// U22 节点角度正负 180 度（高）: the screen shows the node's real facing, negative angles can be typed, the opposite
// number has the same effect as the positive one, and setting only the key node option does not move the rails.
// Covered here: typing a negative angle on a free node; the opposite angle on a connected node leaves its rails as they
// were with no error; key node only leaves the rails as they were. Not covered: the brush's text, switch nodes and
// curves over 90 degrees (the fixture has none), and the drawing of the node.
import {test, expect} from "../lib/test";
import {FIXTURE_Y, chatSince, click, giveOnly, server, serverCommand, teleport, type, useOn, waitFor, waitScreen, widget} from "../lib/game";

const ANGLE = "NodeAngleScreen.textFieldAngle";
const node = (x: number, y: number, z: number) => server.call("/node", {x, y, z});
const sameAngle = (a: number, b: number) => Math.abs(((a - b) % 360 + 540) % 360 - 180) < 0.01;

/** Right-clicks the node with the brush, as a player would, and waits for the node screen. */
async function openNode(x: number, y: number, z: number) {
	await useOn(x, y, z);
	return waitScreen("NodeAngleScreen");
}

test("U22 节点角度：自由节点输入负角度 @U22", async ({marks}) => {
	const [x, y, z] = [0, FIXTURE_Y, 20];
	expect((await serverCommand(`setblock ${x} ${y} ${z} mtr:rail`)).result, "the node is placed").toBe(1);
	await teleport(x + 0.5, y, z - 2.5);
	await giveOnly("mtr:brush");

	await openNode(x, y, z);
	await type(ANGLE, "-135", true);
	expect((await widget(ANGLE)).value, "the field takes a minus sign").toBe("-135");
	await click("NodeAngleScreen.buttonSet");
	await waitScreen(null);

	const after = await waitFor("the node turned", async () => {
		const current = await node(x, y, z);
		return sameAngle(current.angle, -135) ? current : undefined;
	});
	expect(after.rails).toEqual([]);
	const {messages} = await chatSince(marks.chat);
	expect(messages.filter(m => !m.overlay).map(m => m.text), "no error in the chat").toEqual([]);

	// Opened again, the screen shows the angle that was set
	await openNode(x, y, z);
	expect(sameAngle(Number((await widget(ANGLE)).value), -135), `the field shows -135 or the same angle (${(await widget(ANGLE)).value})`).toBe(true);
	await click("NodeAngleScreen.buttonCancel");
	await serverCommand(`setblock ${x} ${y} ${z} minecraft:air`);
});

test("U22 节点角度：反方向和只改关键节点不动轨道 @U22", async ({marks}) => {
	// Between the iron rail and platform B; the line runs along x
	const [x, y, z] = [-15, FIXTURE_Y, 0];
	await teleport(x + 0.5, y, z + 2.5);
	await giveOnly("mtr:brush");
	const before = await node(x, y, z);
	expect(before.rails.length, "the node joins two rails").toBe(2);

	// The opposite of the facing the screen shows has the same effect: nothing changes, no error
	await openNode(x, y, z);
	const shown = Number((await widget(ANGLE)).value);
	expect(before.rails.some((r: any) => sameAngle(r.facingStart, shown) || sameAngle(r.facingStart, shown + 180)), `the screen shows the rails' facing (${shown})`).toBe(true);
	await type(ANGLE, String(shown >= 0 ? shown - 180 : shown + 180), true);
	await click("NodeAngleScreen.buttonSet");
	await waitScreen(null);
	await waitFor("the server to handle the angle", async () => (await chatSince(marks.chat)).messages.length > 0, 3000).catch(() => undefined);
	expect((await node(x, y, z)).rails, "the opposite angle leaves the rails as they were").toEqual(before.rails);
	expect((await chatSince(marks.chat)).messages.filter(m => !m.overlay).map(m => m.text), "no error in the chat").toEqual([]);

	// Only the key node option
	await openNode(x, y, z);
	const keyNode = await widget("NodeAngleScreen.checkboxKeyNode");
	expect(keyNode.value, "not a key node yet").toBe(false);
	await click("NodeAngleScreen.checkboxKeyNode");
	await click("NodeAngleScreen.buttonSet");
	await waitScreen(null);
	const after = await waitFor("the key node set", async () => {
		const current = await node(x, y, z);
		return current.keyNode ? current : undefined;
	});
	expect(after.rails, "setting only the key node leaves the rails as they were").toEqual(before.rails);

	// Back as it was
	await openNode(x, y, z);
	await click("NodeAngleScreen.checkboxKeyNode");
	await click("NodeAngleScreen.buttonSet");
	await waitFor("the key node cleared", async () => !(await node(x, y, z)).keyNode);
});
