// U555 /mtr boarding、/mtr deletepic（中，0.95.0）. Covered here, on the basic fixture (no train out on a route):
// boarding says no train is running; it is offered to operators only; from the console it is for players only.
// deletepic offers the player's own pictures, deletes one by name (gone from the media library), and says when there is
// no such picture. Not covered: boarding a running train and riding it (needs the six lines' trains), deleting another
// player's picture without permission (one player only).
import {test, expect} from "../lib/test";
import {chat, chatSince, player, serverCommand, sleep, suggest, waitFor} from "../lib/game";
import {GIF, pictures} from "../lib/media";

test("U555 /mtr boarding：没有在跑的列车、只给管理员、控制台不能用 @U555", async ({marks}) => {
	const name = await player();
	const wasOperator = /Nothing changed/i.test((await serverCommand(`op ${name}`)).output.join(" "));
	try {
		await sleep(500);
		expect(await waitFor("boarding offered to an operator", async () => (await suggest("/mtr ")).includes("boarding") ? true : null)).toBe(true);
		await chat("/mtr boarding");
		await waitFor("no train running", async () => (await chatSince(marks.chat)).messages.find(m => /这个世界里没有在运行的列车/.test(m.text)));

		const console = await serverCommand("mtr boarding");
		expect(console.result, `from the console: ${console.output.join(" ")}`).toBe(0);

		await serverCommand(`deop ${name}`);
		await waitFor("boarding not offered to a player who isn't an operator", async () => (await suggest("/mtr ")).includes("boarding") ? null : true);
	} finally {
		await serverCommand(`${wasOperator ? "op" : "deop"} ${name}`);
	}
});

test("U555 /mtr deletepic：补全自己的图片、按名字删、名字不对提示 @U555", async ({marks}) => {
	const {post, until, find} = await pictures();
	const sent = await post({name: "u555.gif", data: GIF.toString("base64")});
	expect(sent.ok, `uploaded: ${JSON.stringify(sent)}`).toBe(true);
	const library = await until("u555.gif uploaded", next => !!find(next, "u555.gif"), 30_000);
	const picture = find(library, "u555.gif")!.name;

	const offered = await waitFor("the picture offered", async () => {
		const names = await suggest("/mtr deletepic ");
		return names.includes(picture) ? names : null;
	});
	expect(offered).toContain(picture);

	await chat(`/mtr deletepic ${picture}`);
	await waitFor("deleted", async () => (await chatSince(marks.chat)).messages.find(m => m.text.includes(`已删除图片“${picture}”`)));
	await until("gone from the library", next => !find(next, "u555.gif"));

	await chat("/mtr deletepic nobody:u555-missing.gif");
	await waitFor("no such picture", async () => (await chatSince(marks.chat)).messages.find(m => /没有图片“nobody:u555-missing\.gif”/.test(m.text)));
});
