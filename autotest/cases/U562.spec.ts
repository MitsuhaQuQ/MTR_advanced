// U562 网页车厂刷新路径（中，0.98.0）: the web editor's depot board has 刷新路径 in its 更多 menu, which asks the game to
// find the depot's paths again, as the depot screen's button does. Covered here: the menu item, the notice on the page,
// and the server starting the depot's path generation (its log). Not covered: the sync panel showing the outcome.
import {test, expect} from "../lib/test";
import {readFileSync} from "fs";
import {waitFor} from "../lib/game";
import {openNextEditor} from "../lib/editor";

// The server log file (ui-test.sh passes it): the test kit's log capture keeps warnings and errors only
const generations = () => (readFileSync(process.env.MTM_SERVER_LOG!, "utf8").match(/(Starting|Restarting) path generation/g) || []).length;

test("U562 车厂“更多”里的刷新路径 @U562", async ({page}) => {
	const {errors} = await openNextEditor(page, "depots");
	const before = generations();
	await page.getByRole("button", {name: "更多 ▾"}).first().click();
	await page.getByRole("menuitem", {name: "刷新路径"}).click();
	await expect(page.getByText(/已请求重新找“.+”的路径，结果见同步面板。/)).toBeVisible({timeout: 10_000});
	await waitFor("the server finding the depot's paths again", async () => generations() > before || null, 20_000);
	expect(errors).toEqual([]);
});
