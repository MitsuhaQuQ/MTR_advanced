// U139 网页从零新建线路（高）: in a world with stations and track and no lines, the tree has "＋ 新建线路"; choosing the
// kind of transport, the line group's name and the type, a start and an end builds both directions at once, named with
// "上行" and "下行"; in the draft they show under "（草稿）<line group>"; once applied the game has both routes, with the line
// group's name, the colour and the stations, no services, in no depot; a little later the page's timetable has the
// fastest times.
// Covered here, on the basic fixture (it has two lines already; the panel is the same): the new line panel from the
// operating lines' "＋ 新建线路", 铁路 chosen for it, 甲站 → 丙站 with the way back; the stations along the rails and the
// names it will make; the draft's rows in the tree and the top bar's count; after applying, the server's two routes
// (names, line name, colour, stations in order and reversed, stopping everywhere, managed by the editor, no services, in
// no depot); the page's tree with the new line; the times asked of the server and answered.
// Changes the world while it runs: the two routes are deleted from the dashboard at the end, as a player would, so the
// later tests find the fixture's two routes only (U24 counts them).
// Not covered: a world with no lines at all (a fixture of its own), other kinds of transport, stations chosen between.
import {test, expect} from "../lib/test";
import type {Page} from "@playwright/test";
import {click, closeAll, fixture, list, move, openDashboard, serverData, sleep, switchTab, waitFor, waitScreen} from "../lib/game";
import {openNextEditor} from "../lib/editor";
import {setDashboardSearch} from "../lib/screens";

const LINE = "U139线";
const TYPE = "普通";
const COLOR = "#e91e63";
const LIST = "DashboardScreen.dashboardList";
const ours = (data: any) => data.routes.filter((r: any) => r.line_name === LINE);

test("U139 网页从零新建线路：两个方向一起建、草稿、应用后游戏里有 @U139", async ({page}) => {
	test.setTimeout(5 * 60_000);
	await deleteRoutes("U139", label => label.startsWith(LINE));
	const [A, B, C] = [fixture.platformA, fixture.platformB, fixture.platformC].map(String);

	const {errors} = await openNextEditor(page, "lines");
	try {
		await clearDraft(page);
		const tree = page.locator(".nav-tree");
		const newLine = tree.locator("[data-new-line]");
		await expect(newLine).toHaveText("＋ 新建线路");
		await newLine.click();

		const dialog = page.getByRole("dialog", {name: "新建线路"});
		await expect(dialog).toBeVisible();
		const field = (label: string) => dialog.locator("label", {hasText: new RegExp(`^${label}`)});
		await expect(field("交通方式").locator("select"), "the fixture's platforms are railway ones").toHaveValue("TRAIN");
		await field("线路组名").locator("input").fill(LINE);
		await field("种别").locator("input").fill(TYPE);
		await field("颜色").locator("input").fill(COLOR);
		await field("起点").locator("select").selectOption({label: "甲站"});
		await field("终点").locator("select").selectOption({label: "丙站"});
		await expect(dialog.locator("label", {hasText: "反方向一起建"}).locator("input"), "both directions by default").toBeChecked();

		// The stations along the rails, and both directions' names
		const strip = dialog.getByRole("list", {name: "车站和股道"});
		await expect(strip.locator("li")).toHaveCount(3);
		await expect(strip.locator("li b")).toHaveText(["甲站", "乙站", "丙站"].map(name => new RegExp(`^${name}`)));
		await expect(dialog.getByText(`建出：${LINE} ${TYPE} 上行、${LINE} ${TYPE} 下行`)).toBeVisible();
		await dialog.getByRole("button", {name: "新建", exact: true}).click();
		await expect(dialog).toBeHidden();

		// In the draft: under "（草稿）U139线" in the tree, two items in the top bar
		await expect(tree.getByText(`（草稿）${LINE}`)).toBeVisible();
		await expect(tree.locator(".node[data-created]")).toHaveText([new RegExp(`＋ ${LINE} ${TYPE} 上行`), new RegExp(`＋ ${LINE} ${TYPE} 下行`)]);
		await expect(draftButton(page)).toHaveText("草稿 2 项");

		// Applied: the game has both routes as drafted
		await applyDraft(page);
		const made = await waitFor("the two routes on the server", async () => {
			const found = ours(await serverData());
			return found.length === 2 ? found : undefined;
		}, 20_000);
		const up = made.find((r: any) => String(r.name).endsWith("上行"));
		const down = made.find((r: any) => String(r.name).endsWith("下行"));
		expect(up?.name).toBe(`${LINE} ${TYPE} 上行`);
		expect(down?.name).toBe(`${LINE} ${TYPE} 下行`);
		expect(up.platform_ids.map(String), "上行 stops").toEqual([A, B, C]);
		expect(down.platform_ids.map(String), "下行 stops the other way").toEqual([C, B, A]);
		const data = await serverData();
		for (const r of [up, down]) {
			expect(r.service_type, `${r.name}: the type`).toBe(TYPE);
			expect(r.color, `${r.name}: the colour`).toBe(parseInt(COLOR.slice(1), 16));
			expect(r.pass_stations, `${r.name}: stops everywhere`).toEqual([false, false, false]);
			expect(r.editor_managed, `${r.name}: managed by the editor`).toBe(true);
			expect(r.timetable_services, `${r.name}: no services`).toEqual([]);
			expect(data.depots.some((d: any) => (d.route_ids || []).map(String).includes(String(r.id))), `${r.name}: in no depot`).toBe(false);
		}

		// The page reads them back: the line in the tree with its two types
		const lineRow = tree.locator(".node[data-line][data-section=lines]").filter({hasText: LINE});
		await expect(lineRow).toContainText("2 个种别", {timeout: 15_000});
		await expect(tree.getByText(`（草稿）${LINE}`)).toHaveCount(0);

		// A little later the page has the times along the route from the server
		await page.getByRole("navigation", {name: "工作层"}).locator('a[href="#/timetable"]').click();
		const table = await openRouteTable(page, LINE, String(up.id));
		await expect(table.locator(".tt-tools .hint").first(), "the times answered by the server").not.toContainText("正在向服务端取各站时刻", {timeout: 60_000});
		expect(errors).toEqual([]);
	} finally {
		await clearDraft(page).catch(() => undefined);
		await deleteRoutes("U139", label => label.startsWith(LINE));
	}
});

// ---- The page ----

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
	await expect(table.locator(".tt-tools b").first()).toBeVisible();
	return table;
}

// ---- The game ----

/** Deletes, from the dashboard, the routes the search finds whose label matches */
async function deleteRoutes(search: string, match: (label: string) => boolean) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	await setDashboardSearch(search);
	await sleep(300);
	for (let i = 0; i < 6; i++) {
		const row = (await list(LIST)).rows.find(r => !r.header && match(r.label));
		if (!row) break;
		await move(row.x + 10, row.y + row.height / 2);
		await click(`${LIST}.buttonDelete`);
		await waitScreen("DeleteConfirmationScreen");
		await click("DeleteConfirmationScreen.buttonYes");
		await waitScreen("DashboardScreen");
		await sleep(500);
	}
	await setDashboardSearch("");
	await closeAll();
	await waitFor(`no route matching ${search} left`, async () =>
		!(await serverData()).routes.some((r: any) => String(r.name).includes(search)), 10_000);
}
