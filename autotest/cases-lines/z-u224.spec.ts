// U224 指定回库存车线、U242 运用的出车方式（高）: in the depot layer, a duty's panel (views/DutyDetail.tsx) has "回库存车线"
// (自动, or any siding of the duty's pool) and "出车方式" (优先从这条存车线出 / 只从这条存车线出 / 不指定). Set to siding B, the
// train goes back to B after the duty, not to A; with no way back to B it goes to another siding of the pool and the sync
// panel says so. "只从" A: when A has no train, B's train doesn't stand in (the duty waits, joining late if too late);
// "不指定": the siding with a train and the quickest way out; "优先从" as before.
// Needs a depot with two sidings of one train type and duties: in the six lines' fixture only lines 1 and 4 have that,
// and neither runs trains (run.spec.ts EXPECTED_WITHOUT_TRAINS), the basic fixture's depots have one siding each and no
// timetables. So this file belongs in autotest/cases-lines, named z-u224.spec.ts and added to AFTER in
// playwright.config.ts (it changes the editor's draft); it covers the panel only, and the draft is emptied, never applied.
// Covered here: both selects, their choices (the pool's sidings, the three ways), each choice taken into the draft and
// shown on the depot's row, "出车存车线" offered with two sidings, no page errors.
// Not covered (needs fixture work: a depot with two sidings of one type whose trains run, e.g. line 3 with a second
// siding beside the first): the trains going back to B, the fallback when B has no way back with its sync message, "只从"
// leaving a duty without a train rather than taking B's, "不指定" taking the quickest. The second test below is that,
// marked fixme until the fixture has such a depot.
import {test, expect} from "../lib/test";
import type {Locator, Page} from "@playwright/test";
import {Line, lines} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

const draftButton = (page: Page) => page.getByRole("button", {name: /^(草稿 \d+ 项|没有草稿)$/});

async function discardDrafts(page: Page) {
	const button = draftButton(page);
	await expect(button).toBeVisible();
	if (/没有草稿/.test(await button.innerText())) return;
	await button.click();
	const panel = page.getByRole("dialog", {name: "草稿的改动"});
	await panel.getByRole("button", {name: "清空全部草稿…"}).click();
	await panel.getByRole("alert").getByRole("button", {name: "确定"}).click();
	await expect(button).toHaveText(/没有草稿/);
	await panel.getByRole("button", {name: "关闭"}).click();
	await page.waitForTimeout(1000);
}

/** The select under a label of the duty panel */
const selectIn = (panel: Locator, label: string) => panel.locator("label").filter({hasText: label}).locator("select").first();

/**
 * Opens the depot in the tree and chooses its duties in turn until one whose panel offers "回库存车线" (a duty with
 * services that doesn't stay out overnight); returns the panel and the duty's siding, or null.
 */
async function dutyWithReturn(page: Page, line: Line): Promise<{panel: Locator, siding: string} | null> {
	const depotRow = page.locator(`#tree .node[data-depot="${String(line.depot)}"]`);
	await expect(depotRow, `line ${line.layout}'s depot in the tree`).toBeVisible({timeout: 20_000});
	if (await depotRow.getAttribute("aria-expanded") !== "true") await depotRow.click();
	const slots = page.locator(`#tree .node[data-slot][data-of-depot="${String(line.depot)}"]`);
	await expect(slots.first().or(page.getByText("没有运用").first())).toBeVisible();
	const count = await slots.count();
	for (let i = 0; i < count; i++) {
		const slot = slots.nth(i);
		await slot.click();
		// DutyDetail's <section aria-label="运用：车 N"> in the inspector
		const panel = page.getByRole("region", {name: /^运用：车 /}).first();
		await expect(panel).toBeVisible();
		if (await selectIn(panel, "回库存车线").count()) return {panel, siding: (await slot.getAttribute("data-siding"))!};
	}
	return null;
}

test("U224 U242 运用面板：回库存车线和出车方式进草稿 @lines @U224 @U242", async ({page}) => {
	const candidates = lines.filter(l => (l.sidings || []).length >= 2);
	expect(candidates.length, "a depot with two sidings in the six lines' fixture (lines 1 and 4)").toBeGreaterThan(0);
	const {errors} = await openNextEditor(page, "depots");
	await discardDrafts(page);
	try {
		let found: {panel: Locator, siding: string, line: Line} | null = null;
		for (const line of candidates) {
			const duty = await dutyWithReturn(page, line);
			if (duty) {
				found = {...duty, line};
				break;
			}
		}
		test.skip(!found, "no duty with services in a depot with two sidings (lines 1 and 4 had none planned)");
		const {panel, siding, line} = found!;

		// U224: 自动 and the pool's sidings
		const back = selectIn(panel, "回库存车线");
		const backOptions = await back.locator("option").evaluateAll(options => options.map(o => ({value: (o as HTMLOptionElement).value, text: o.textContent || ""})));
		expect(backOptions[0], "自动 first").toEqual({value: "0", text: "自动（车池里那时最早要用车的）"});
		const poolSidings = backOptions.slice(1).map(o => o.value);
		test.skip(poolSidings.length < 2, `line ${line.layout}'s two sidings are not one pool (different train types)`);
		expect(poolSidings, "the duty's own siding is offered").toContain(siding);
		const other = poolSidings.find(id => id !== siding)!;
		await back.selectOption(other);
		await expect(back, "B chosen").toHaveValue(other);
		await expect(draftButton(page), "the choice is in the draft").toHaveText(/草稿 [1-9]\d* 项/);
		await expect(page.locator(`#tree .node[data-depot="${String(line.depot)}"] [title="运用有没应用的草稿"]`), "the depot's row says its duties have a draft").toBeVisible();
		await back.selectOption("0");
		await expect(back).toHaveValue("0");

		// "出车存车线" is offered with two sidings in the pool
		await expect(selectIn(panel, "出车存车线"), "出车存车线 with two sidings").toBeVisible();

		// U242: the three ways a duty's train comes out, each taken
		const start = selectIn(panel, "出车方式");
		const startOptions = await start.locator("option").evaluateAll(options => options.map(o => [(o as HTMLOptionElement).value, o.textContent || ""]));
		expect(startOptions).toEqual([["prefer", "优先从这条存车线出"], ["only", "只从这条存车线出"], ["any", "不指定（车池里有车、出库最快的）"]]);
		for (const value of ["only", "any", "prefer"]) {
			await start.selectOption(value);
			await expect(start, `出车方式 ${value}`).toHaveValue(value);
		}
		await expect(panel.getByText("重新生成运用时，出车方式和出车存车线按生成结果重来。")).toBeVisible();
	} finally {
		await discardDrafts(page);
	}
	expect(errors).toEqual([]);
});

// The trains doing as the duty says: needs a depot with two sidings of one train type whose trains run (fixture work).
test.fixme("U224 U242 回库存车线和出车方式在游戏里生效 @lines @U224 @U242", async () => {
	// With such a depot (sidings A and B, one pool) and its duties planned:
	// 1. U224: a duty of A set to 回库存车线 B, applied, "刷新路径" in the depot's screen; sample trainsNow() until that
	//    duty's train is back in the depot: it stands on B (TrainSample.siding), not A. Then B made unreachable (its rail
	//    removed): the train goes to another siding of the pool and the sync panel says so.
	// 2. U242 "只从": every train of A sent out, a duty of A set to 只从 A: B's train stays in B (its siding's train count
	//    unchanged) and the duty runs once A has a train again, joining late if need be.
	// 3. U242 "不指定": the duty's train comes from whichever siding has a train with the quickest way out.
	// Each change put back afterwards (the depot's duties as planned, the draft emptied).
});
