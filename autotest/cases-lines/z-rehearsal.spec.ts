// U490 编排后预演并提示（高，0.83.0）: once a depot's duties or its routes' timetables change and settle, the server
// simulates its routes' day in the background and the web editor's sync panel warns of what it found: late services with
// what held them up, trains held on the way, deadlocks, services no train runs. Only warnings: the plan isn't changed.
// The six lines have line 2's express late every round (its depot's way out runs through 五站's turn back rail, which the
// express stands on) and line 11's short dead end: both warned of.
// Covered here: the warnings for lines 2 and 11, and the server saying it rehearsed; every warning is attached to the report.
// Not covered: whether the other lines' warnings are right (looked at).
import {test, expect} from "../lib/test";
import {lines, serverLog} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

test("U490 预演：2 号线快车和 11 号线有提示 @lines @U490", async ({page}, testInfo) => {
	expect(serverLog().includes("the rehearsal of depot"), "the server rehearsed the depots' plans").toBe(true);
	const routeNames = (layout: number) => lines.find(l => l.layout === layout)!.routes.map(r => r.name.split("|")[0]);
	const {errors} = await openNextEditor(page, "depots");
	await page.getByRole("button", {name: /同步状态/}).click();
	const panel = page.getByRole("dialog", {name: "同步状态"});
	const warned = (layout: number) => panel.getByText(new RegExp(`^预演：(${routeNames(layout).join("|")}).*晚点超过 30 秒`));
	await expect(warned(2).first(), "line 2's express is warned of").toBeVisible({timeout: 120_000});
	await expect(panel.getByText(/^预演：2号线快车.*晚点超过 30 秒/).first(), "the express itself").toBeVisible();
	await expect(warned(11).first(), "line 11 is warned of").toBeVisible();
	await testInfo.attach("rehearsal warnings", {body: (await panel.getByText(/^预演：/).allTextContents()).join(String.fromCharCode(10))});
	expect(errors).toEqual([]);
});
