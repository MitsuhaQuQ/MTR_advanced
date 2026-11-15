// U344 网页增删和排序停站（高）: right-click a route, "编辑停站…", "增删和排序停站…": move up, move down, delete, insert a
// station after one (station and platform chosen), switch passing; "发给游戏" changes the route's stops in the game at
// once, and its timetable, one-off changes, depot orders, through running and duty ends follow; deleting a stop that a
// through link, an order or a duty end hangs on is refused with what hangs there; after the depot's paths are refreshed
// the times are right again.
// Covered here, on the fixture's "测试线" (甲站 → 乙站 → 丙站): moving a stop down and back up (the send button off again
// once the order is as it was); deleting 乙站 and sending (the server has 甲站, 丙站, the services kept); inserting 乙站
// after 甲站 as a passing stop and sending (甲站, 乙站 passed, 丙站); stopping there again and sending; refreshing West
// Depot's path. And, in the draft only: a through link from "测试线" at 丙站 to "测试线回程" makes deleting 丙站 from
// "测试线回程" refused, naming the link ("直通接入"); the draft is then emptied.
// Changes the world while it runs: the route's stops; they end as they started (a restore runs if the test stops halfway).
// Stops sent from the page make the route the editor's (EditorActions.applyRoute): it is handed back to the game at the
// end ("交回游戏内编辑"), as the later tests change its timetable in the game.
// Not covered: one-off changes, depot orders and duty ends being renumbered (the fixture has none), the times after the
// refresh (the fixture's routes have no way back to their depots, see T28).
import {test, expect} from "../lib/test";
import type {Locator, Page} from "@playwright/test";
import {fixture, serverData, waitFor} from "../lib/game";
import {openNextEditor, routeMenu, routeRow} from "../lib/editor";
import {refreshDepotPath} from "../lib/screens";

const routeOf = (data: any, id: unknown) => data.routes.find((r: any) => String(r.id) === String(id));
const stopsOf = async (id: unknown) => {
	const r = routeOf(await serverData(), id);
	return {platforms: (r.platform_ids as unknown[]).map(String), pass: (r.pass_stations as boolean[]).slice(), services: (r.timetable_services as unknown[]).length};
};

test("U344 增删和排序停站：上下移、删除、插入、通过，发给游戏 @U344", async ({page}) => {
	test.setTimeout(6 * 60_000);
	const routeId = String(fixture.route);
	const [A, B, C] = [fixture.platformA, fixture.platformB, fixture.platformC].map(String);
	const start = await stopsOf(routeId);
	expect(start.platforms, "the fixture's stops").toEqual([A, B, C]);
	expect(start.pass, "stopping everywhere").toEqual([false, false, false]);

	const {errors} = await openNextEditor(page, "timetable");
	let finished = false;
	try {
		await clearDraft(page);

		// ---- Refused: a stop a through link hangs on (draft only) ----
		let table = await openRouteTable(page, "测试线", routeId);
		await addWebService(page, table, "09:00");
		const from = await serviceHead(page, "09:00").first().getAttribute("data-svc");
		await table.locator(`td[data-svc="${from}"][data-stop="2"][data-kind="arr"]`).click({button: "right"});
		await page.getByText(/^设为接出点/).click();
		table = await openRouteTable(page, "测试线回程", String(fixture.routeBack));
		await addWebService(page, table, "09:40");
		const to = await serviceHead(page, "09:40").first().getAttribute("data-svc");
		await table.locator(`td[data-svc="${to}"][data-stop="0"][data-kind="dep"]`).click({button: "right"});
		await page.getByText(/^设为接入点/).click();

		await page.getByRole("navigation", {name: "工作层"}).locator('a[href="#/lines"]').click();
		let order = await openStopOrder(page, fixture.routeBack);
		await order.locator("ol > li").first().getByRole("button", {name: "删除", exact: true}).click();
		await expect(order.getByRole("alert"), "the delete refused, naming the link").toContainText(/这一站上还挂着：.*直通接入/);
		await expect(order.locator("ol > li"), "the stop stays").toHaveCount(3);
		await order.getByRole("button", {name: "返回"}).click();
		await page.keyboard.press("Escape");
		await clearDraft(page);

		// ---- Up and down: back as it was, nothing to send ----
		order = await openStopOrder(page, routeId);
		const rows = order.locator("ol > li");
		const names = () => rows.locator("span.truncate").allTextContents();
		await expect(rows).toHaveCount(3);
		const send = order.getByRole("button", {name: "发给游戏"});
		await expect(send, "nothing changed yet").toBeDisabled();
		await rows.nth(0).getByRole("button", {name: "下移"}).click();
		expect((await names()).map(n => n.split(" ")[0]), "甲站 moved down").toEqual(["乙站", "甲站", "丙站"]);
		await expect(send).toBeEnabled();
		await rows.nth(1).getByRole("button", {name: "上移"}).click();
		expect((await names()).map(n => n.split(" ")[0]), "and back up").toEqual(["甲站", "乙站", "丙站"]);
		await expect(send, "the order as it was: nothing to send").toBeDisabled();

		// ---- Delete 乙站 and send ----
		await rows.nth(1).getByRole("button", {name: "删除", exact: true}).click();
		await expect(rows).toHaveCount(2);
		await sendToGame(order);
		const deleted = await waitFor("the server to have the stop deleted", async () => {
			const now = await stopsOf(routeId);
			return now.platforms.length === 2 ? now : undefined;
		}, 15_000);
		expect(deleted.platforms).toEqual([A, C]);
		expect(deleted.services, "the services kept").toBe(start.services);

		// ---- Insert 乙站 after 甲站, passing, and send ----
		order = await openStopOrder(page, routeId, 2);
		await insertAfterFirst(order, "乙站");
		const inserted = order.locator("ol > li").nth(1);
		await expect(inserted.getByText("新加")).toBeVisible();
		await inserted.getByRole("checkbox").check();
		await sendToGame(order);
		const back = await waitFor("the server to have the stop back", async () => {
			const now = await stopsOf(routeId);
			return now.platforms.length === 3 ? now : undefined;
		}, 15_000);
		expect(back.platforms).toEqual([A, B, C]);
		expect(back.pass, "乙站 passed").toEqual([false, true, false]);
		expect(back.services, "the services kept").toBe(start.services);

		// ---- Stop there again ----
		// The page reads the route again a few seconds after a send, and the panel starts from the stops it has when it
		// opens: opened again until it has 乙站 passed
		await expect.poll(async () => {
			order = await openStopOrder(page, routeId, 3);
			if (await order.locator("ol > li").nth(1).getByRole("checkbox").isChecked()) return true;
			await page.keyboard.press("Escape");
			await expect(order).toBeHidden();
			await page.waitForTimeout(1000);
			return false;
		}, {timeout: 30_000, message: "the page has 乙站 passed"}).toBe(true);
		await order.locator("ol > li").nth(1).getByRole("checkbox").uncheck();
		// The checklist row: switching passing is sent with "发给游戏" like the rest (StopOrder in StopsEditor.tsx counts
		// only the order and the number of stops as a change, 2026-11-15)
		await expect(order.getByRole("button", {name: "发给游戏"}), "passing switched alone can be sent").toBeEnabled();
		await sendToGame(order);
		await waitFor("乙站 stopped at again", async () => (await stopsOf(routeId)).pass.every(p => !p), 15_000);

		// The depot's paths found again for the changed route
		await refreshDepotPath("西车厂");
		expect(errors).toEqual([]);
		finished = true;
	} finally {
		await clearDraft(page).catch(() => undefined);
		// A restore that fails after the test failed must not hide why the test failed
		await restore(page, routeId, [A, B, C]).catch(error => {
			if (finished) throw error;
			console.error("U344: the route's stops could not be put back", error);
		});
		await handBack(page, routeId).catch(error => {
			if (finished) throw error;
			console.error("U344: the route could not be handed back to the game", error);
		});
	}
});

// ---- The stops panel ----

/** "编辑停站…" from the lines layer's tree, then "增删和排序停站…"; waits until the page has the route's stops as given */
async function openStopOrder(page: Page, routeId: unknown, stops?: number): Promise<Locator> {
	if (!/#\/lines$/.test(page.url())) await page.getByRole("navigation", {name: "工作层"}).locator('a[href="#/lines"]').click();
	// The tree counts the route's stops: the page has read the change the panel starts from
	if (stops !== undefined) await expect(await routeRow(page, routeId)).toContainText(`${stops} 站`, {timeout: 15_000});
	await routeMenu(page, routeId, "编辑停站…");
	const stopsPanel = page.getByRole("dialog", {name: /^停站：/});
	await stopsPanel.getByRole("button", {name: "增删和排序停站…"}).click();
	const order = page.getByRole("dialog", {name: /^增删和排序停站：/});
	await expect(order).toBeVisible();
	return order;
}

/** "发给游戏", which asks in place first, then "确定"; the panel closes once it is sent */
async function sendToGame(order: Locator) {
	await order.getByRole("button", {name: "发给游戏"}).click();
	await order.getByRole("button", {name: "确定", exact: true}).click();
	await expect(order).toBeHidden({timeout: 10_000});
}

/** "在后面插入" on the first stop: the station (its first platform), "插入" */
async function insertAfterFirst(order: Locator, station: string) {
	await order.locator("ol > li").first().getByRole("button", {name: "在后面插入"}).click();
	await order.locator("label", {hasText: /^车站/}).locator("select").selectOption({label: station});
	await order.getByRole("button", {name: "插入", exact: true}).click();
	await expect(order.locator("ol > li")).toHaveCount(3);
}

/** Puts the route's stops back as the fixture has them, from the states this test can leave it in */
async function restore(page: Page, routeId: string, platforms: string[]) {
	const now = await stopsOf(routeId);
	if (now.platforms.join() === platforms.join() && now.pass.every(p => !p)) return;
	await page.keyboard.press("Escape");
	let order = await openStopOrder(page, routeId, now.platforms.length);
	if (now.platforms.length === 3) {
		// Passing alone can't be switched back with "发给游戏" (see above): 乙站 taken out, and put in again below
		await order.locator("ol > li").nth(1).getByRole("button", {name: "删除", exact: true}).click();
		await sendToGame(order);
		await waitFor("乙站 taken out", async () => (await stopsOf(routeId)).platforms.length === 2, 15_000);
		order = await openStopOrder(page, routeId, 2);
	}
	// Put in new, it is stopped at
	await insertAfterFirst(order, "乙站");
	await sendToGame(order);
	await waitFor("the route's stops put back", async () => {
		const after = await stopsOf(routeId);
		return after.platforms.join() === platforms.join() && after.pass.every(p => !p);
	}, 15_000);
}

// ---- The timetable layer ----

/** Gives the route back to the game ("交回游戏内编辑" in its summary table, applied), if the page's sends made it the editor's */
async function handBack(page: Page, routeId: string) {
	const managed = async () => Boolean(routeOf(await serverData(), routeId).editor_managed);
	if (!(await managed())) return;
	await page.keyboard.press("Escape");
	const table = await openRouteTable(page, "测试线", routeId);
	await table.getByRole("button", {name: "交回游戏内编辑"}).click();
	await draftButton(page).click();
	const review = page.getByRole("dialog", {name: "草稿的改动"});
	await review.getByRole("button", {name: "应用", exact: true}).click();
	await expect(draftButton(page)).toHaveText("没有草稿", {timeout: 30_000});
	await page.keyboard.press("Escape");
	await waitFor("the route handed back to the game", async () => !(await managed()), 15_000);
}

const draftButton = (page: Page) => page.locator("header:has(h1)").getByRole("button", {name: /^(草稿 \d+ 项|没有草稿|正在应用…)$/});

/** Empties the draft (it is shared by every page of this game and kept between tests) */
async function clearDraft(page: Page) {
	// The draft comes from the game a moment after the page connects
	await page.waitForTimeout(2000);
	if (((await draftButton(page).textContent()) || "").trim() === "没有草稿") return;
	await draftButton(page).click();
	const review = page.getByRole("dialog", {name: "草稿的改动"});
	await review.getByRole("button", {name: "清空全部草稿…"}).click();
	await review.getByRole("button", {name: "确定", exact: true}).click();
	await expect(draftButton(page)).toHaveText("没有草稿");
	await page.keyboard.press("Escape");
	await expect(review).toBeHidden();
}

function lineRow(page: Page, name: string) {
	return page.locator(".nav-tree .node[data-line]:not([data-view])")
		.filter({has: page.locator("span.font-medium", {hasText: new RegExp(`^${name}$`)})});
}

/** Opens a route's summary timetable from the timetable layer's tree: its line, the route, its "汇总时刻表" chip */
async function openRouteTable(page: Page, lineName: string, routeId: string) {
	if (!/#\/timetable$/.test(page.url())) await page.getByRole("navigation", {name: "工作层"}).locator('a[href="#/timetable"]').click();
	const tree = page.locator(".nav-tree");
	const line = lineRow(page, lineName);
	const route = tree.locator(`.node[data-route="${routeId}"]:not([data-view])`);
	const chip = tree.locator(`.node[data-route="${routeId}"][data-view="table"]`);
	await expect(line).toBeVisible({timeout: 20_000});
	for (let i = 0; i < 3 && !(await route.isVisible()); i++) {
		await line.click();
		await page.waitForTimeout(300);
	}
	await expect(route).toBeVisible();
	for (let i = 0; i < 3 && !(await chip.isVisible()); i++) {
		await route.click();
		await page.waitForTimeout(300);
	}
	await chip.click();
	const table = page.locator("[data-summary-table]");
	await expect(table.locator(".tt-tools b").first()).toHaveText(new RegExp(`^${lineName} → `));
	return table;
}

function serviceHead(page: Page, time: string) {
	return page.locator("[data-summary-table] th.svc-head").filter({has: page.locator(".time", {hasText: new RegExp(`^${time}`)})});
}

/** Adds a service to the shown table, into the draft (the end and interval boxes keep what was typed last: emptied) */
async function addWebService(page: Page, table: Locator, time: string) {
	const startBox = table.getByRole("textbox", {name: "添加班次", exact: true});
	await startBox.fill(time);
	await table.getByRole("textbox", {name: "结束 09:10", exact: true}).fill("");
	await table.getByRole("textbox", {name: "间隔 5", exact: true}).fill("");
	await startBox.press("Enter");
	await expect(serviceHead(page, time).first()).toBeVisible();
}
