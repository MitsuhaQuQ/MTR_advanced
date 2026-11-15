// U431 临时处置在新页面（高）: a train late with no contingency plan on for it is told in the sync panel with "临时处置…",
// which opens the form there (not the old page): turning back, where, what after, stations to pass, a spare train taking
// over. Nothing chosen is refused; sent, the panel says the train is handled by a dispatcher's setting, with "解除".
// Runs after run.spec.ts: lines 7 and 11 run late enough by then to be told (their timetable and layout, see run.spec).
// Covered here: the form, refusing nothing chosen, sending a turn back at the nearest station, the handled line and
// ending it again. Not covered (looked at): the train in the game turning back as set.
import {test, expect} from "../lib/test";
import {openNextEditor} from "../lib/editor";

test("U431 临时处置：同步状态里填写、发送、解除 @lines @U431", async ({page}, testInfo) => {
	const {errors} = await openNextEditor(page, "depots");
	await page.getByRole("button", {name: /同步状态/}).click();
	const panel = page.getByRole("dialog", {name: "同步状态"});
	const alarm = panel.locator("li").filter({hasText: "没有生效的故障预案"}).filter({has: page.getByRole("button", {name: "临时处置…"})}).first();
	await expect(alarm, "a late train with no plan on for it").toBeVisible({timeout: 60_000});
	const text = (await alarm.textContent()) || "";
	await testInfo.attach("alarm", {body: text});
	await alarm.getByRole("button", {name: "临时处置…"}).click();
	const form = alarm.locator("[data-adhoc]");
	await expect(form).toBeVisible();
	const train = await form.getAttribute("data-adhoc");
	for (const field of ["临时折返", "折返以后"]) await expect(form.getByLabel(field), `the form has ${field}`).toBeVisible();
	await expect(form.getByText("由备车接替余下的运用")).toBeVisible();

	// Nothing chosen: refused there
	await form.getByLabel("临时折返").selectOption("none");
	await form.getByRole("button", {name: "发送"}).click();
	await expect(form.getByRole("alert")).toHaveText("什么处置都没选");

	// Turn back at the nearest station that can, carrying on in service
	await form.getByLabel("临时折返").selectOption("nearest");
	await form.getByRole("button", {name: "发送"}).click();
	const handled = panel.locator("li").filter({hasText: "按临时处置处理"});
	await expect(handled.first(), "the panel says the train is handled by a dispatcher's setting").toBeVisible({timeout: 30_000});
	await testInfo.attach("handled", {body: `train ${train}: ` + ((await handled.first().textContent()) || "")});
	const before = await handled.count();
	await handled.first().getByRole("button", {name: "解除"}).click();
	await expect.poll(async () => (await panel.getByText("已发送").count()) > 0 || (await handled.count()) < before, {message: "ended", timeout: 30_000}).toBe(true);
	expect(errors).toEqual([]);
});
