// U467 带图片的画不被 /kill 删（中，0.78.2）: /kill leaves out paintings showing an imported picture; plain paintings and
// one whose picture was cleared go as before, and once its picture is cleared a painting can be killed.
// Covered here: what the server keeps after /kill (with a type selector, as @e would also kill the test player). Not
// covered: breaking one by hand.
import {test, expect} from "../lib/test";
import {FIXTURE_Y, serverCommand, teleport, waitFor} from "../lib/game";

const [X, Z] = [60, 70];
const PICTURE = String.raw`{\"image\":\"u467:photo.png\",\"width\":1.0,\"height\":1.0}`;
const CLEARED = String.raw`{\"image\":\"\"}`;

async function count(tag: string): Promise<number> {
	return (await serverCommand(`execute if entity @e[type=minecraft:painting,tag=${tag}]`)).result;
}

function summon(x: number, tag: string, image?: string) {
	const extra = image === undefined ? "" : `,mtr_image:"${image}"`;
	return serverCommand(`summon minecraft:painting ${x} ${FIXTURE_Y + 1} ${Z - 1} {facing:2b,variant:"minecraft:kebab",Tags:["u467","${tag}"]${extra}}`);
}

test("U467 带图片的画：/kill 不删，清掉图片后照常 @U467", async () => {
	const y = FIXTURE_Y;
	await teleport(X + 0.5, y, Z - 4.5);
	await serverCommand(`fill ${X - 1} ${y} ${Z} ${X + 8} ${y + 3} ${Z} minecraft:stone`);
	await serverCommand(`fill ${X - 1} ${y} ${Z - 1} ${X + 8} ${y + 3} ${Z - 1} minecraft:air`);
	try {
		await summon(X, "u467picture", PICTURE);
		await summon(X + 3, "u467plain");
		await summon(X + 6, "u467cleared", CLEARED);
		await waitFor("three paintings up", async () => (await count("u467")) === 3 || undefined);

		await serverCommand("kill @e[type=minecraft:painting,tag=u467]");
		expect(await count("u467picture"), "the painting with a picture stays").toBe(1);
		expect(await count("u467plain"), "a plain painting goes").toBe(0);
		expect(await count("u467cleared"), "one whose picture was cleared goes").toBe(0);

		// Its picture cleared, it goes too
		await serverCommand(`data merge entity @e[type=minecraft:painting,tag=u467picture,limit=1] {mtr_image:"${CLEARED}"}`);
		await serverCommand("kill @e[type=minecraft:painting,tag=u467]");
		expect(await count("u467picture"), "cleared, it can be killed").toBe(0);
	} finally {
		await serverCommand(`data merge entity @e[type=minecraft:painting,tag=u467,limit=1] {mtr_image:"${CLEARED}"}`).catch(() => undefined);
		await serverCommand("kill @e[type=minecraft:painting,tag=u467]");
		await serverCommand("kill @e[type=minecraft:item]");
		await serverCommand(`fill ${X - 1} ${y} ${Z - 1} ${X + 8} ${y + 3} ${Z} minecraft:air`);
	}
});
