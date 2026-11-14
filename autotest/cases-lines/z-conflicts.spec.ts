// 冲突进路提示与尽头折返线兜底（0.80.0，MTR-待验证.md 的 A）: line 11 keeps line 8's old layout, both halves turning back
// through one dead end east of 三站 and B's trains turning three times along A's way the other way; line 8 now turns each
// half back in front of a platform of its own. The server tells line 11's depot's conflicting routes in its log and the
// web editor's sync panel warns of them with the better layouts; line 8's depot has none.
// Covered here: the log line and the warning for line 11 only. The trains of line 11 not deadlocking is the run test's.
import {test, expect} from "../lib/test";
import {lines, serverLog} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

test("冲突进路：第 11 条线提示、8 号线不提示 @lines @conflicts", async ({page}) => {
	const log = serverLog();
	const name = (layout: number) => lines.find(l => l.layout === layout)!.depotName.split("|")[0];
	// The log has the depot's whole name, its Chinese part garbled on some consoles: matched by the English part
	const english = (layout: number) => lines.find(l => l.layout === layout)!.depotName.split("|")[1] + " has conflicting routes";
	const told = log.split("\n").filter(line => line.includes("has conflicting routes"));
	expect(told.some(line => line.includes(english(11))), "line 11's depot has its conflicting routes told").toBe(true);
	expect(told.some(line => line.includes(english(8))), "line 8's depot has none: each half turns back in front of its own platform").toBe(false);

	const {errors} = await openNextEditor(page, "depots");
	await page.getByRole("button", {name: /同步状态/}).click();
	const panel = page.getByRole("dialog", {name: "同步状态"});
	await expect(panel.getByText(new RegExp(`车厂${name(11)}的运用里存在大量冲突进路`)).first(), "the warning for line 11").toBeVisible({timeout: 20_000});
	await expect(panel.getByText(new RegExp(`车厂${name(8)}的运用里存在大量冲突进路`)), "none for line 8").toHaveCount(0);
	expect(errors).toEqual([]);
});
