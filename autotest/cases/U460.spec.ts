// U458 / U460 / U461 站台端门（中，0.76.8–0.77.0）: put down from the item, a platform end door swings towards the
// platform (INWARD) with no sneaking; the brush turns it left to right (MIRRORED), both halves, open or shut as it was, and
// back again; it still opens and shuts by hand. All four styles.
// Covered here: the block states the server keeps. Not covered: how the door looks (PlatformEndDoorTest checks the
// models and the collision), and sneaking to swing it the other way.
import {test, expect} from "../lib/test";
import {FIXTURE_Y, giveOnly, serverCommand, sleep, teleport, useOn, waitFor} from "../lib/game";

const STYLES = ["platform_end_apg", "platform_end_psd", "platform_end_psd_2", "platform_end_apg_2"];
const [X, Z] = [60, 60];

/** Whether the block there is that end door with these states, as the server has it */
async function is(x: number, y: number, z: number, block: string, states: string): Promise<boolean> {
	return (await serverCommand(`execute if block ${x} ${y} ${z} mtr:${block}[${states}]`)).result > 0;
}

async function until(what: string, check: () => Promise<boolean>) {
	await waitFor(what, async () => (await check()) || undefined, 5000, 200);
}

test("U460 U461 端门：放下朝站台开，刷子切换左右开 @U460 @U461 @U458", async () => {
	const y = FIXTURE_Y;
	await serverCommand(`fill ${X - 2} ${y - 1} ${Z - 2} ${X + 10} ${y - 1} ${Z + 2} minecraft:stone`);
	await serverCommand(`fill ${X - 2} ${y} ${Z - 2} ${X + 10} ${y + 3} ${Z + 2} minecraft:air`);
	try {
		for (let i = 0; i < STYLES.length; i++) {
			const block = STYLES[i];
			const x = X + i * 3;
			await teleport(x + 0.5, y, Z - 2.5);
			await giveOnly(`mtr:${block}`);
			await useOn(x, y - 1, Z, "up");
			await until(`${block} put down`, () => is(x, y, Z, block, "half=lower"));
			// Towards the platform without sneaking (U460), both halves the same
			expect(await is(x, y, Z, block, "inward=true,open=false,mirrored=false"), `${block}: swings towards the platform`).toBe(true);
			expect(await is(x, y + 1, Z, block, "half=upper,inward=true,mirrored=false"), `${block}: the upper half the same`).toBe(true);

			// The brush turns it left to right, both halves, still shut (U461)
			await giveOnly("mtr:brush");
			await useOn(x, y, Z, "north");
			await until(`${block} turned left to right`, () => is(x, y, Z, block, "mirrored=true"));
			expect(await is(x, y, Z, block, "open=false,inward=true"), `${block}: still shut, swinging the same way`).toBe(true);
			expect(await is(x, y + 1, Z, block, "mirrored=true"), `${block}: the upper half turned too`).toBe(true);

			// Opened by hand it stays turned; the brush on the open door turns it back and leaves it open
			await giveOnly("minecraft:stick");
			await useOn(x, y, Z, "north");
			await until(`${block} opened`, () => is(x, y, Z, block, "open=true,mirrored=true"));
			await giveOnly("mtr:brush");
			await useOn(x, y, Z, "north");
			await until(`${block} turned back`, () => is(x, y, Z, block, "mirrored=false"));
			expect(await is(x, y, Z, block, "open=true"), `${block}: still open`).toBe(true);
			expect(await is(x, y + 1, Z, block, "mirrored=false,open=true"), `${block}: the upper half as well`).toBe(true);
			await sleep(100);
		}
	} finally {
		await serverCommand(`fill ${X - 2} ${y} ${Z - 2} ${X + 10} ${y + 3} ${Z + 2} minecraft:air`);
	}
});
