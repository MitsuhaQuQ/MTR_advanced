// U90 网页编辑器：冲突的草稿保留但不能提交（高）and U478 网页提示和谁的编辑冲突（高）: a route's timetable changed in the
// page's draft and not applied, then changed in the game: within seconds the page tells (U478: before writing, the sync
// panel says writing now would conflict), the draft is kept; written anyway, that route isn't written and is marked as a
// conflict, with "保留我的" (以最新版本为基础，覆盖对方的修改) and "用游戏里的" (放弃草稿，用游戏里的); the other routes in
// the same apply are written; with only the conflicting route left "应用" is off; discarding takes the game's version;
// keeping the draft over the game's change applies it with the draft's content.
// Covered here, with the one test player as both "A" (the page) and "B" (the game's timetable screen): round 1, a
// service drafted on each fixture route, one added in the game to "测试线": the sync panel's warning, the draft kept,
// applying writes "测试线回程" only and leaves "测试线" in the draft as a conflict (banner with both choices, sync panel
// "草稿先不发", "应用" off), "放弃草稿，用游戏里的" leaves the game's service on the page; round 2, a service drafted, the
// game's service deleted, "以最新版本为基础，覆盖对方的修改" then "应用" writes the draft (the game's service back with
// the new one). Every service added is deleted again in the game's timetable screen at the end, after both routes are
// handed back to the game ("交回游戏内编辑"): a timetable applied from the page makes the route the editor's
// (EditorActions.applyRoute), and the game's timetable screen then changes nothing.
// Not covered: the other player's name in the messages ("B 正在编辑它", "和 B 的修改冲突": one player here, the page's
// own), a change of the route's name only not counting as a conflict, two pages applying at the same moment, the chat
// message; whether the page shows the game's version while the draft is still there (the summary table shows the draft).
import {test, expect} from "../lib/test";
import type {Locator, Page} from "@playwright/test";
import {click, closeAll, fixture, list, move, openDashboard, serverData, sleep, switchTab, waitFor, waitScreen, widget} from "../lib/game";
import {openNextEditor} from "../lib/editor";
import {addService, deleteService, setDashboardSearch} from "../lib/screens";

const LIST = "DashboardScreen.dashboardList";
const servicesOf = async (id: unknown) =>
	(((await serverData()).routes.find((r: any) => String(r.id) === String(id)) || {}).timetable_services || []).length as number;

test("U90 U478 冲突的草稿：提示、保留不发、放弃或覆盖 @U90 @U478", async ({page}, testInfo) => {
	test.setTimeout(8 * 60_000);
	const routeId = String(fixture.route), backId = String(fixture.routeBack);
	const n0 = await servicesOf(routeId), m0 = await servicesOf(backId);

	const {errors} = await openNextEditor(page, "timetable");
	const banner = page.getByRole("status");
	const discard = banner.getByRole("button", {name: "放弃草稿，用游戏里的"});
	const keep = banner.getByRole("button", {name: "以最新版本为基础，覆盖对方的修改"});
	let finished = false;
	try {
		await clearDraft(page);

		// ---- Round 1: both routes drafted, "测试线" changed in the game ----
		let table = await openRouteTable(page, "测试线", routeId);
		await addWebService(page, table, "11:00");
		table = await openRouteTable(page, "测试线回程", backId);
		await addWebService(page, table, "11:15");
		await expect(draftButton(page)).toHaveText("草稿 2 项");

		await openTimetableExact("测试线 Test Line");
		await addService("11:30:00");
		await closeAll();
		await waitFor("the game's service on the server", async () => (await servicesOf(routeId)) === n0 + 1, 10_000);

		// U478: told before writing; the draft is kept
		const told = await syncText(page, /^测试线 → 丙站：游戏里在(你的)?草稿之后/);
		await testInfo.attach("sync panel before writing", {body: told});
		await expect(draftButton(page), "the draft kept").toHaveText("草稿 2 项");

		// Written anyway: "测试线回程" goes, "测试线" stays in the draft, marked as a conflict
		await expect(topApply(page), "the other route can still be applied").toBeEnabled();
		await draftButton(page).click();
		const review = page.getByRole("dialog", {name: "草稿的改动"});
		await review.getByRole("button", {name: "应用", exact: true}).click();
		await expect(draftButton(page), "the conflicting route left in the draft").toHaveText("草稿 1 项", {timeout: 30_000});
		await page.keyboard.press("Escape");
		await waitFor("the other route written", async () => (await servicesOf(backId)) === m0 + 1, 15_000);
		expect(await servicesOf(routeId), "the conflicting route not written").toBe(n0 + 1);
		await expect(banner, "the banner names the route").toContainText("测试线 → 丙站");
		await expect(discard).toBeVisible();
		await expect(keep).toBeVisible();
		await expect(topApply(page), "nothing else to apply: 应用 off").toBeDisabled();
		const marked = await syncText(page, /^测试线 → 丙站：(和 .+ 的修改冲突|游戏里在草稿之后改过，草稿先不发)/);
		await testInfo.attach("sync panel after writing", {body: marked});

		// "用游戏里的": the draft goes, the page shows the game's timetable
		await discard.click();
		await expect(draftButton(page)).toHaveText("没有草稿");
		table = await openRouteTable(page, "测试线", routeId);
		await expect(serviceHead(page, "11:30").first(), "the game's service").toBeVisible({timeout: 15_000});
		await expect(serviceHead(page, "11:00"), "not the draft's").toHaveCount(0);

		// ---- Round 2: drafted again, the game's service deleted, the draft kept over it ----
		// The last apply's result closed first, so the banner tells only what comes now
		await banner.getByRole("button", {name: "关闭"}).click().catch(() => undefined);
		await addWebService(page, table, "12:00");
		await expect(draftButton(page)).toHaveText("草稿 1 项");
		await openTimetableExact("测试线 Test Line");
		await deleteService("11:30:00");
		await closeAll();
		await waitFor("the game's service deleted on the server", async () => (await servicesOf(routeId)) === n0, 10_000);
		await syncText(page, /^测试线 → 丙站：游戏里在(你的)?草稿之后/);
		// Marked as a conflict while drafting, or else on writing
		if (await topApply(page).isEnabled()) {
			await draftButton(page).click();
			await review.getByRole("button", {name: "应用", exact: true}).click();
			await expect(draftButton(page)).toHaveText("草稿 1 项", {timeout: 30_000});
			await page.keyboard.press("Escape");
		}
		await expect(keep).toBeVisible({timeout: 15_000});
		expect(await servicesOf(routeId), "not written while in conflict").toBe(n0);
		await keep.click();
		await expect(topApply(page), "kept over the game's change: can be applied").toBeEnabled();
		await applyDraft(page);
		// The draft's timetable: the game's service it was made from, and its own
		await waitFor("the draft written", async () => (await servicesOf(routeId)) === n0 + 2, 15_000);
		await expect(serviceHead(page, "12:00").first()).toBeVisible({timeout: 15_000});
		await expect(serviceHead(page, "11:30").first()).toBeVisible();
		expect(errors).toEqual([]);
		finished = true;
	} finally {
		await clearDraft(page).catch(() => undefined);
		// Every service the test added, wherever it got to
		const cleanup = async () => {
			for (const [lineName, id] of [["测试线", routeId], ["测试线回程", backId]]) await handBack(page, lineName, id);
			for (const [label, times] of [["测试线 Test Line", ["11:00:00", "11:30:00", "12:00:00"]], ["测试线回程 Test Line Back", ["11:15:00"]]] as const) {
				await openTimetableExact(label);
				for (const time of times) await deleteService(time).catch(() => undefined);
				await closeAll();
			}
			await waitFor("the services as they were", async () => (await servicesOf(routeId)) === n0 && (await servicesOf(backId)) === m0, 10_000);
		};
		await cleanup().catch(error => {
			if (finished) throw error;
			console.error("U90: the services added could not all be deleted", error);
		});
	}
});

// ---- The page ----

const draftButton = (page: Page) => page.locator("header:has(h1)").getByRole("button", {name: /^(草稿 \d+ 项|没有草稿|正在应用…)$/});
/** The top bar's "应用" (the review panel has one too, but it is closed here) */
const topApply = (page: Page) => page.getByRole("button", {name: "应用", exact: true}).first();

/** Opens the sync panel, waits for a problem matching the pattern, closes it again; returns every problem's text */
async function syncText(page: Page, pattern: RegExp): Promise<string> {
	await page.getByRole("button", {name: /^同步状态/}).click();
	const panel = page.getByRole("dialog", {name: "同步状态"});
	await expect(panel.getByText(pattern).first()).toBeVisible({timeout: 15_000});
	const text = (await panel.locator("li").allTextContents()).join("\n");
	await page.keyboard.press("Escape");
	await expect(panel).toBeHidden();
	return text;
}

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

/** Gives a route the page's applies made the editor's back to the game ("交回游戏内编辑", applied), if it is the editor's */
async function handBack(page: Page, lineName: string, id: string) {
	const managed = async () => Boolean(((await serverData()).routes.find((r: any) => String(r.id) === id) || {}).editor_managed);
	if (!(await managed())) return;
	const table = await openRouteTable(page, lineName, id);
	await table.getByRole("button", {name: "交回游戏内编辑"}).click();
	await applyDraft(page);
	await waitFor(`${lineName} handed back to the game`, async () => !(await managed()), 15_000);
}

/** Applies the draft from its review panel and waits until it has gone to the game */
async function applyDraft(page: Page) {
	await draftButton(page).click();
	const review = page.getByRole("dialog", {name: "草稿的改动"});
	await review.getByRole("button", {name: "应用", exact: true}).click();
	await expect(draftButton(page)).toHaveText("没有草稿", {timeout: 30_000});
	await page.keyboard.press("Escape");
	await expect(review).toBeHidden();
}

/** Opens a route's summary timetable from the timetable layer's tree: its line, the route, its "汇总时刻表" chip */
async function openRouteTable(page: Page, lineName: string, routeId: string) {
	const tree = page.locator(".nav-tree");
	const line = tree.locator(".node[data-line]:not([data-view])").filter({has: page.locator("span.font-medium", {hasText: new RegExp(`^${lineName}$`)})});
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

// ---- The game ----

/** Dashboard → routes → the route whose label is exactly this → edit → timetable */
async function openTimetableExact(label: string) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	await setDashboardSearch(label);
	// The list is filtered on the next frames: read before that, it still has the rows of the search before
	await sleep(500);
	const row = await waitFor(`${label} in the route list`, async () => (await list(LIST)).rows.find(r => !r.header && r.label === label));
	// Off the list first: the search can leave the route in the row the mouse is already over, and the list takes the
	// row under the mouse again only when the mouse moves
	await move(1, 1);
	await sleep(100);
	await move(row.x + 10, row.y + row.height / 2);
	await waitFor("the edit button on the row", async () => (await widget(`${LIST}.buttonEdit`)).visible, 3000);
	await click(`${LIST}.buttonEdit`);
	await waitScreen("EditRouteScreen");
	await click("EditRouteScreen.buttonTimetable");
	return waitScreen("TimetableScreen");
}
