// U488 逻辑线路不再设联络线（中，0.82.5）: a connecting line is a stretch of track open to every line, set on the
// ownership map, not an ownership: the new logical line panel has no connecting checkbox, says where it is set instead,
// and a new line is saved as an ordinary one. Not covered: a line marked before keeping its mark (web tests do that).
import {test, expect} from "../lib/test";
import {openNextEditor} from "../lib/editor";

test("U488 新建逻辑线路的面板没有联络线选项 @U488", async ({page}) => {
	const {errors} = await openNextEditor(page, "owners");
	await page.locator('[data-new-logical]').first().click();
	const panel = page.getByRole("dialog", {name: "新建逻辑线路"});
	await expect(panel).toBeVisible();
	await expect(panel.getByText("联络线：所有运营线路都能走"), "no connecting checkbox").toHaveCount(0);
	await expect(panel.getByRole("checkbox"), "no checkbox at all: name and colour only").toHaveCount(0);
	await expect(panel.getByText(/联络线不在这里设/), "it says where connecting lines are set").toBeVisible();
	await panel.getByRole("button", {name: "取消"}).click();
	expect(errors).toEqual([]);
});
