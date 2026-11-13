// U443 游戏内浏览图片文件夹（高，0.74.0）: right-clicking a painting with the brush, the picture list is a tree: the
// original painting and the uploaders at the top, then folders, then pictures; a folder opens on a click, the first row
// goes back up, backspace with nothing typed goes up, typing searches below the open folder only, and the list opens on
// the folder of the picture chosen. Nothing is applied: the screen is closed with Cancel.
import {test, expect} from "../lib/test";
import {click, client, giveOnly, key, player, serverCommand, sleep, state, teleport, waitFor, waitScreen, widget, FIXTURE_Y} from "../lib/game";
import {pictures} from "../lib/media";

test("U443 刷子选图片：上传者、文件夹一层层进出、搜索、停在所选图片的文件夹 @U443", async () => {
	const {upload, post, until, find} = await pictures();
	await upload("u443a.gif", "画/甲");
	await upload("u443b.gif");
	const name = await player();

	// A painting on a wall, the player in front of it with the brush
	await serverCommand(`setblock 10 ${FIXTURE_Y} 31 minecraft:stone`);
	await serverCommand(`kill @e[type=minecraft:painting,distance=..20,x=10,y=${FIXTURE_Y},z=30]`);
	await serverCommand(`summon minecraft:painting 10 ${FIXTURE_Y} 30 {facing:2b,variant:"minecraft:kebab"}`);
	await teleport(10, FIXTURE_Y, 27);
	await giveOnly("mtr:brush");
	await waitFor("the painting near the player", async () => { try { return (await client.call("/use-entity", {type: "painting"})).result; } catch { return null; } });
	await waitScreen("ImportedPaintingScreen");

	const texts = async () => (await state()).texts;
	const row = (pattern: RegExp) => waitFor(`a row ${pattern}`, async () => (await texts()).find(t => pattern.test(t.text)));
	const clickRow = async (pattern: RegExp) => { const t = await row(pattern); await click({x: t.x + 4, y: t.y + 3}); await sleep(150); };
	const pathLine = async () => (await row(/^上传图片/)).text;

	// The top: the original painting and the uploaders
	await click("ImportedPaintingScreen.choose");
	expect(await pathLine()).toMatch(/^上传图片  —  /);
	await row(/^原版画/);
	await clickRow(new RegExp(`^> ${name}  \\(\\d+\\)`));
	expect(await pathLine()).toContain(`上传图片 / ${name}`);
	expect((await texts()).some(t => /:u443b\.gif$/.test(t.text)), "the picture at the player's top").toBe(true);
	await clickRow(/^> 画  \(1\)/);
	await clickRow(/^> 甲  \(1\)/);
	expect(await pathLine()).toContain(`${name} / 画 / 甲`);
	await clickRow(/:u443a\.gif$/);
	expect((await widget("ImportedPaintingScreen.choose")).text).toContain("u443a.gif");

	// Opened again, on the folder of the picture chosen; backspace goes up; typing searches below the open folder only
	await click("ImportedPaintingScreen.choose");
	expect(await pathLine()).toContain(`${name} / 画 / 甲`);
	await key("BACKSPACE");
	await sleep(150);
	expect(await pathLine()).toContain(`${name} / 画`);
	expect(await pathLine()).not.toContain("甲");
	await client.call("/type", {chars: "u443"});
	await sleep(150);
	const shown = (await texts()).map(t => t.text);
	expect(shown.some(text => /:u443a\.gif$/.test(text)), "the picture below 画").toBe(true);
	expect(shown.some(text => /:u443b\.gif$/.test(text)), "not the one outside 画").toBe(false);
	// The first row goes up
	await key("ESCAPE");
	await sleep(150);
	await clickRow(/^< 上一级/);
	expect(await pathLine()).toContain(`上传图片 / ${name}`);
	await key("ESCAPE");
	await click({text: "取消"} as never);
	await waitScreen(null);

	// Cleaning up
	await serverCommand(`kill @e[type=minecraft:painting,distance=..20,x=10,y=${FIXTURE_Y},z=30]`);
	await serverCommand(`setblock 10 ${FIXTURE_Y} 31 minecraft:air`);
	const library = await until("the list", () => true);
	for (const file of ["u443a.gif", "u443b.gif"]) {
		await post({action: "delete", name: find(library, file)!.name});
	}
	await post({action: "folder", op: "delete", owner: library.self!, path: "画"});
	await until("cleaned up", next => !next.entries.some(entry => /:u443[ab]\.gif$/.test(entry.name)));
});
