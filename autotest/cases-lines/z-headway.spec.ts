// U481 时刻表提示追踪间隔不够（高，0.82.0）: a route whose services follow closer than its block sections and train length
// allow (line 7: every 30 seconds) is told in the web editor's sync panel as a yellow warning, from the game's timetable
// check; the routes every 6 minutes are not.
// Covered here: the warning for line 7 and none for lines 8 and 9. Not covered (looked at in game): the timetable screen's
// red conflict text, and the warning going once the services are spaced out.
import {test, expect} from "../lib/test";
import {lines} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

const TOLD = "班和前一班隔得比列车能跑的还近";

test("U481 追踪间隔不够：7 号线有黄色提示、6 分钟一班的线没有 @lines @U481", async ({page}) => {
	const routeNames = (layout: number) => lines.find(l => l.layout === layout)!.routes.map(r => r.name.split("|")[0]);
	const {errors} = await openNextEditor(page, "depots");
	await page.getByRole("button", {name: /同步状态/}).click();
	const panel = page.getByRole("dialog", {name: "同步状态"});
	// Each route not checked yet is asked for every 10 seconds, so the warning may take a while to come
	const warning = panel.getByText(new RegExp(`^(${routeNames(7).join("|")}).*${TOLD}`)).first();
	await expect(warning, "a warning for line 7").toBeVisible({timeout: 240_000});
	const text = (await warning.textContent())!;
	const [, gap, needed] = text.match(/只隔 (\d+) 秒，按闭塞分区和车长至少要隔 (\d+) 秒/) || [];
	expect(Number(gap), `the gap told: ${text}`).toBe(30);
	expect(Number(needed), "more is needed than the 30 seconds").toBeGreaterThan(30);
	for (const layout of [8, 9]) {
		await expect(panel.getByText(new RegExp(`^(${routeNames(layout).join("|")}).*${TOLD}`)), `none for line ${layout}`).toHaveCount(0);
	}
	expect(errors).toEqual([]);
});
