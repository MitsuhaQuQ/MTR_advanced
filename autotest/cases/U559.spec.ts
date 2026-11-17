// U559 /kill 不清掉 MTR 的座位和升降机（中，0.97.0）, U560 拆掉屏蔽门后站台块恢复原来的朝向和材质（中，0.97.0).
// Covered here: the seat entity each player has (what riding a train uses) is left alone by a /kill that also takes
// other entities, and killed by one naming it alone; a platform block turned to a straight screen door's facing goes back
// to its own when the door is taken away, and a curved screen door's glass on a platform block doesn't turn it.
// Not covered: lift cars (they need a lift built), the look itself (the facing is checked).
import {test, expect} from "../lib/test";
import {player, serverCommand, waitFor, FIXTURE_Y} from "../lib/game";

test("U559 /kill @e 不清掉座位实体，单独点名才清 @U559", async () => {
	const name = await player();
	await serverCommand(`tp ${name} 20 ${FIXTURE_Y + 1} 30`);
	// The player's seat follows them (it is made again every few ticks, so it is told by its type, not its id)
	await waitFor("the player's seat nearby", async () => (await serverCommand(`execute if entity @e[type=mtr:seat,x=20,y=${FIXTURE_Y},z=30,distance=..10]`)).result > 0 || null);
	await serverCommand(`summon minecraft:pig 22 ${FIXTURE_Y + 1} 30 {NoAI:1b}`);
	const killed = await serverCommand(`kill @e[type=!minecraft:player,x=20,y=${FIXTURE_Y},z=30,distance=..10]`);
	// The pig alone, not the seat with it (the command's result is how many it killed)
	expect(killed.result, "only the pig killed").toBe(1);
	expect((await serverCommand(`execute if entity @e[type=minecraft:pig,x=20,y=${FIXTURE_Y},z=30,distance=..10]`)).result, "the pig is gone").toBe(0);

	// Named on its own it is killed
	await waitFor("the player's seat nearby", async () => (await serverCommand(`execute if entity @e[type=mtr:seat,x=20,y=${FIXTURE_Y},z=30,distance=..10]`)).result > 0 || null);
	const seat = await serverCommand(`kill @e[type=mtr:seat,x=20,y=${FIXTURE_Y},z=30,distance=..10]`);
	expect(seat.result, `seat killed: ${seat.output.join(" ")}`).toBeGreaterThan(0);
});

test("U560 拆掉直线屏蔽门后站台块回到原来的朝向；曲线门不转它 @U560", async () => {
	const [x, y, z] = [30, FIXTURE_Y, 34];
	const platformIs = async (properties: string) => (await serverCommand(`execute if block ${x} ${y} ${z} mtr:platform[${properties}]`)).result > 0;
	await serverCommand(`fill ${x} ${y} ${z} ${x} ${y + 3} ${z} minecraft:air`);
	await serverCommand(`setblock ${x} ${y} ${z} mtr:platform[facing=north]`);
	expect(await platformIs("facing=north,door_type=none,base_facing=0")).toBe(true);

	// A straight screen door glass facing south turns it, remembering north
	await serverCommand(`setblock ${x} ${y + 2} ${z} mtr:psd_glass[facing=south,half=upper]`);
	await serverCommand(`setblock ${x} ${y + 1} ${z} mtr:psd_glass[facing=south,half=lower]`);
	await waitFor("turned to the door", async () => await platformIs("facing=south,door_type=psd") || null, 3000);
	expect(await platformIs("base_facing=0"), "its own facing kept").toBe(false);

	// The door taken away: north again, nothing kept
	await serverCommand(`setblock ${x} ${y + 2} ${z} minecraft:air`);
	await serverCommand(`setblock ${x} ${y + 1} ${z} minecraft:air`);
	await waitFor("back to its own facing", async () => await platformIs("facing=north,door_type=none,side=0,base_facing=0") || null, 3000);

	// Curved glass on it: the screen door top, but not turned
	await serverCommand(`setblock ${x} ${y + 2} ${z} mtr:curved_psd_glass[facing=south,half=upper]`);
	await serverCommand(`setblock ${x} ${y + 1} ${z} mtr:curved_psd_glass[facing=south,half=lower]`);
	await waitFor("the screen door top", async () => await platformIs("door_type=psd") || null, 3000);
	expect(await platformIs("facing=north,side=0,base_facing=0"), "not turned by curved glass").toBe(true);
	await serverCommand(`fill ${x} ${y} ${z} ${x} ${y + 3} ${z} minecraft:air`);
});
