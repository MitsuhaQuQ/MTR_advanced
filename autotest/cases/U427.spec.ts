// U427 打开编辑器就是新版、U428 旧地址跳到新页面（高，第⑨步）: the address the game opens is the new editor with its
// layer rail and nothing of the old page's switches; loading it again reads the data again; the old addresses
// /next/#/<layer> and /audio.html go to the root address on that layer.
// Covered here: the page the game's address opens, a reload, three old addresses, no errors in the console. Not covered:
// opening it from the dashboard button itself (the address is the client's /editor-url).
import {test, expect} from "../lib/test";
import {client} from "../lib/game";

const watch = (page: import("@playwright/test").Page) => {
	const errors: string[] = [];
	// A file the page asks for and doesn't get is named by its address rather than the console's general line
	page.on("console", message => { if (message.type() === "error") errors.push(message.text().startsWith("Failed to load resource") ? `${message.text()} ${new URL(message.location().url || "x:/").pathname}` : message.text()); });
	page.on("response", response => { if (response.status() >= 400) errors.push(`${response.status()} ${new URL(response.url()).pathname}`); });
	page.on("pageerror", error => errors.push(String(error)));
	return errors;
};

test("U427 打开编辑器就是新版，刷新后照样读到数据 @U427", async ({page}) => {
	const errors = watch(page);
	const {url} = await client.call("/editor-url");
	await page.goto(url);
	await expect(page.getByRole("navigation", {name: "工作层"}), "the layer rail of the new page").toBeVisible({timeout: 20_000});
	await expect(page.getByText("已连接游戏")).toBeVisible({timeout: 20_000});
	expect(await page.getByText("回到旧版").count(), "no way back to the old page").toBe(0);
	expect(await page.getByText("新版预览").count(), "no preview switch").toBe(0);
	await page.reload();
	await expect(page.getByRole("navigation", {name: "工作层"})).toBeVisible({timeout: 20_000});
	await expect(page.getByText("已连接游戏")).toBeVisible({timeout: 20_000});
	expect(errors).toEqual([]);
});

test("U428 旧地址跳到新页面的对应层 @U428", async ({page}) => {
	const errors = watch(page);
	const {url} = await client.call("/editor-url");
	// The game's address first, which keeps the key for the page, as a player's browser has it
	await page.goto(url);
	await expect(page.getByText("已连接游戏")).toBeVisible({timeout: 20_000});
	const origin = new URL(url).origin;
	for (const [address, layer] of [["/next/#/timetable", "timetable"], ["/next/#/depots", "depots"], ["/audio.html", "media"]]) {
		await page.goto(origin + address);
		await expect.poll(() => new URL(page.url()).pathname, `${address}: to the root address`).toBe("/");
		await expect.poll(() => new URL(page.url()).hash, `${address}: on its layer`).toBe("#/" + layer);
		await expect(page.getByRole("navigation", {name: "工作层"}).locator(`a[href="#/${layer}"]`)).toHaveAttribute("aria-current", "page", {timeout: 20_000});
		await expect(page.getByText("已连接游戏")).toBeVisible({timeout: 20_000});
	}
	expect(errors).toEqual([]);
});
