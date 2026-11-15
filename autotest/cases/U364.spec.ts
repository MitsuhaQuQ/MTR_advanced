// U364 没有逻辑线路时行为不变（高）: a world without logical lines (an old save upgraded to 0.64.0, or the basic fixture)
// works its owners out as before: the server's "worked out the owners of the track sections" line has no "by their
// logical lines"; the navigator's "逻辑归属" says there are none yet; the ownership layer's brushes are the operating lines.
// Covered here, on the basic fixture (which never had logical lines; U361 and U362 take theirs away again before this
// runs): the last owners line in the server's log, the network settings the client has, the owners the map reads (no
// logical line on any section), the navigator's text and the first-draft row, the panel's brushes. Reads only.
// Not covered: upgrading an old save to 0.64.0 and comparing its paths and duties with before (needs an old save), the
// old owners set by hand and connecting lines on it (U355 covers owners set by hand on the fixture).
import {test, expect} from "../lib/test";
import {serverLog} from "../lib/lines";
import {openNextEditor} from "../lib/editor";
import type {Page} from "@playwright/test";

/** An editor endpoint called from the page itself, with the key and origin the page has */
async function api(page: Page, path: string, body?: unknown): Promise<any> {
	return page.evaluate(async ([path, body]) => {
		const key = sessionStorage.getItem("mtr-editor-key") || "";
		const response = await fetch("/api/" + path, body === undefined
			? {headers: {"X-MTR-Editor-Key": key}, cache: "no-store"}
			: {method: "POST", headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json"}, body: JSON.stringify(body), cache: "no-store"});
		return response.json();
	}, [path, body] as const);
}

test("U364 没有逻辑线路：归属照旧算，页面写还没有逻辑线路 @U364", async ({page}) => {
	const {errors} = await openNextEditor(page, "owners");

	// The game's settings: no logical lines, no section in one, no line choosing any
	const settings = (await api(page, "data")).networkSettings;
	expect(settings.logicalLines, "no logical lines").toEqual([]);
	expect(settings.railLines, "no section in a logical line").toEqual({});
	expect(settings.lineTracks, "no operating line choosing logical lines").toEqual({});

	// The server worked the owners out without them, the last time it did
	const lines = serverLog().split("\n").filter(line => line.includes("worked out the owners of the track sections"));
	expect(lines.length, "the server has worked the owners out (MTM_SERVER_LOG)").toBeGreaterThan(0);
	expect(lines[lines.length - 1]).not.toContain("by their logical lines");

	// The owners the map reads: ready, every section with owners, none in a logical line
	await expect.poll(async () => (await api(page, "network", {mode: "TRAIN", fresh: false, version: -1})).owners.ready, {timeout: 20_000}).toBe(true);
	const owners = (await api(page, "network", {mode: "TRAIN", fresh: false, version: -1})).owners;
	expect(owners.rails.length, "sections with owners").toBeGreaterThan(0);
	expect(owners.rails.filter((entry: any[]) => entry[3] !== ""), "no section in a logical line").toEqual([]);

	// The navigator's "逻辑归属"
	const navigator = page.getByRole("complementary", {name: "导航"});
	await expect(navigator.getByText("逻辑归属", {exact: true})).toBeVisible({timeout: 20_000});
	await expect(navigator.getByText("还没有逻辑线路：现在按寻路边界和站台自动算归属")).toBeVisible();
	await expect(navigator.locator("[data-generate-logical]")).toHaveText("按现在的归属生成初稿…");
	await expect(navigator.locator("[data-logical]")).toHaveCount(0);

	// The panel's brushes are the operating lines, open to all, worked out again; no table of logical lines
	const panel = page.getByRole("complementary", {name: "归属画笔"});
	await expect(panel.getByRole("heading", {name: "归属画笔"})).toBeVisible();
	for (const name of ["只归 测试线", "只归 测试线回程", "对所有线路开放", "恢复自动归属"]) {
		await expect(panel.getByRole("button", {name, exact: true}), name).toBeVisible();
	}
	await expect(panel.getByText("运营线路走哪些逻辑线路")).toHaveCount(0);
	await expect(panel.getByRole("button", {name: "橡皮：移出逻辑线路"})).toHaveCount(0);
	expect(errors).toEqual([]);
});
