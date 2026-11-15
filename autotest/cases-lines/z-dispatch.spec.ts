// U270 调度台显示, U491 按线路筛选, U271 扣停和延长停站, U272 下一站清客、指定回库、撤销, U402 新版的调度台卡片 (高): the new page's
// dispatch console, a card for each train out on the line, the latest first, read again every couple of seconds; its
// commands reach the game's trains. Runs after run.spec.ts, with the six lines' trains out.
// Covered here: the cards and their order, the rail system filter, the cards read again; four trains on four lines: one
// made to stop 60 s longer stands that long at its next stop, one held stands until let go and then leaves, one cleared at
// its next stop and sent back goes into its depot, one sent back and then the command cancelled shows it and then not.
// Not covered (looked at): the blocks coloured on the map, the train names on it; turning back early (U402's 提前折返),
// which needs a station with a crossover where the train is.
import {test, expect} from "../lib/test";
import {sleep} from "../lib/game";
import {Line, lines, trainsNow, TrainSample} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

const lineOf = (train: TrainSample) => lines.find(line => train.z > line.z - 40 && train.z < line.z + 60);
const inDepot = (train: TrainSample) => !train.onRoute || lines.some(l => l.depotBox && train.x >= l.depotBox[0] && train.x <= l.depotBox[2] && train.z >= l.z + l.depotBox[1] && train.z <= l.z + l.depotBox[3]);
const atPlatform = (line: Line, train: TrainSample) => (line.stationX || [0, 200, 400, 640, 840]).some(sx => train.x >= sx - 2 && train.x <= sx + 62);
/** Lines running on time, in the order trains are taken from */
const PREFERRED = [3, 5, 6, 10, 9, 8, 2];

test("调度台：卡片、筛选、延长停站、扣停放行、清客回库、撤销 @lines @U270 @U491 @U271 @U272 @U402", async ({page}, testInfo) => {
	// On top of the time waited for the sampling (lib/test.ts set the timeout to include it)
	test.setTimeout(test.info().timeout + 13 * 60_000);
	const {errors} = await openNextEditor(page, "dispatch");
	const cards = page.locator("li[data-console-card]");
	await expect(cards.first(), "a card for a train").toBeVisible({timeout: 30_000});

	// U270: how many are out, read again every couple of seconds; the filter by rail system
	// The console's own count (the old console, hidden, has one too)
	const summary = page.getByRole("complementary", {name: "调度台"}).getByText(/列车在线上/).first();
	await expect(summary).toBeVisible();
	const ages: string[] = [];
	for (let i = 0; i < 6; i++) {
		ages.push((await summary.textContent()) || "");
		await sleep(1000);
	}
	await testInfo.attach("console summary", {body: ages.join("\n")});
	expect(ages.some(text => /，[0-2] 秒前/.test(text)), "read again every couple of seconds").toBe(true);
	const all = await cards.count();
	const systems = page.getByRole("combobox", {name: "路网"});
	const options = await systems.locator("option").allTextContents();
	expect(options.length, "rail systems to filter by").toBeGreaterThan(1);
	await systems.selectOption({index: 1});
	await expect.poll(async () => cards.count(), {message: "fewer cards for one rail system"}).toBeLessThan(all);
	await systems.selectOption({index: 0});
	await expect.poll(async () => cards.count()).toBeGreaterThanOrEqual(all - 2);
	// U491 (0.83.3): narrowed to one line, every card is one of its routes'; all lines again shows them all
	const byLine = page.getByRole("combobox", {name: "线路"});
	const lineNames = (await byLine.locator("option").allTextContents()).slice(1);
	expect(lineNames.length, "U491: the lines of the trains out to choose from").toBeGreaterThan(1);
	await byLine.selectOption({index: 1});
	await expect.poll(async () => cards.count(), {message: "U491: fewer cards for one line"}).toBeLessThan(all);
	const routeTexts = await cards.evaluateAll(items => items.map(item => item.querySelector("span")?.textContent || ""));
	expect(routeTexts.every(text => text.startsWith(lineNames[0])), `U491: only ${lineNames[0]}'s trains: ${routeTexts.join(", ")}`).toBe(true);
	await byLine.selectOption({index: 0});
	await expect.poll(async () => cards.count()).toBeGreaterThanOrEqual(all - 2);

	// U402: the latest first: once a card has no lateness tag, none after it has
	const lateTags = await cards.evaluateAll(items => items.map(item => /晚点 \d+ 分钟/.test(item.textContent || "")));
	const firstOnTime = lateTags.indexOf(false);
	expect(firstOnTime < 0 || !lateTags.slice(firstOnTime).includes(true), `late trains first: ${lateTags.join(",")}`).toBe(true);

	// Four trains on four lines running on time, out on the line, with a card offering commands
	const ids = await cards.filter({has: page.getByRole("button", {name: "扣停", exact: true})}).evaluateAll(items => items.map(item => item.getAttribute("data-console-card")));
	const chosen: {id: string, line: Line}[] = [];
	// Taken as they come by: a few tries a couple of seconds apart
	for (let attempt = 0; attempt < 60 && chosen.length < 4; attempt++, await sleep(2000)) for (const layout of PREFERRED) {
		if (chosen.some(each => each.line.layout === layout)) continue;
		const now = (await trainsNow()).trains;
		// Running between two stations of its line, so its next stop is a platform ahead (a train on a turn-back track
		// stood there 147 s and the longer stop went unseen, 2026-11-15)
		const between = (t: TrainSample) => { const sx = lineOf(t)!.stationX || [0, 200, 400, 640, 840]; return t.speed > 0.02 && t.x > sx[0] + 62 && t.x < sx[sx.length - 1] - 2 && !atPlatform(lineOf(t)!, t); };
		const train = now.find(t => ids.includes(String(t.id)) && !inDepot(t) && lineOf(t)?.layout === layout && between(t));
		if (train && chosen.length < 4) chosen.push({id: String(train.id), line: lineOf(train)!});
	}
	if (chosen.length < 4) {
		const seen = (await trainsNow()).trains.filter(t => ids.includes(String(t.id))).map(t => `${lineOf(t)?.layout}号线 ${String(t.id).slice(-4)} x ${Math.round(t.x)} z ${Math.round(t.z - (lineOf(t)?.z || 0))} speed ${t.speed.toFixed(3)}`);
		await testInfo.attach("trains with cards", {body: seen.join(String.fromCharCode(10))});
	}
	expect(chosen.length, `four trains to command (cards ${ids.length}, chosen on lines ${chosen.map(c => c.line.layout).join(",")})`).toBe(4);
	const [extended, held, cleared, cancelled] = chosen;
	const card = (id: string) => page.locator(`li[data-console-card="${id}"]`);
	const button = (id: string, name: string) => card(id).getByRole("button", {name, exact: true});

	await button(extended.id, "延长停站…").click();
	await card(extended.id).locator("input[type=number]").fill("60");
	await button(extended.id, "发送").click();
	await button(held.id, "扣停").click();
	await button(cleared.id, "下一站清客后回库").click();
	await button(cancelled.id, "指定回库").click();
	await expect(card(cancelled.id), "U272: the card says it goes back").toContainText("本段跑完回库", {timeout: 15_000});
	await expect(card(held.id).locator("span", {hasText: /^扣停$/}), "U402: the card tagged held").toBeVisible({timeout: 15_000});
	await expect(card(extended.id), "the card says it stops longer").toContainText(/延长停站(?!…)/, {timeout: 15_000});
	await button(cancelled.id, "撤销").click();
	await expect(card(cancelled.id), "U272: cancelled").not.toContainText("本段跑完回库", {timeout: 15_000});

	// The trains, every second
	const log: string[] = [];
	const stand = new Map<string, {from: number, to: number, platform: boolean}[]>();
	let heldSince = 0, released = 0, leftAfterRelease = 0, clearedHome = 0;
	const start = Date.now();
	while (Date.now() - start < 9 * 60_000) {
		const sample = await trainsNow();
		for (const {id, line} of [extended, held, cleared]) {
			const train = sample.trains.find(t => String(t.id) === id);
			if (!train) continue;
			const standing = train.speed < 0.005;
			const list = stand.get(id) || [];
			const last = list[list.length - 1];
			if (standing && last && last.to >= sample.millis - 2500) last.to = sample.millis;
			else if (standing) list.push({from: sample.millis, to: sample.millis, platform: atPlatform(line, train)});
			stand.set(id, list);
			if (id === cleared.id && !clearedHome && inDepot(train)) clearedHome = sample.millis;
		}
		// Let the held train go once it has stood 90 s
		const heldStands = stand.get(held.id) || [];
		const heldLast = heldStands[heldStands.length - 1];
		if (!released && heldLast && heldLast.to - heldLast.from >= 90_000) {
			heldSince = heldLast.from;
			await button(held.id, "放行").click();
			released = Date.now();
			log.push(`let go after standing ${Math.round((heldLast.to - heldLast.from) / 1000)} s`);
		}
		if (released && !leftAfterRelease) {
			const train = sample.trains.find(t => String(t.id) === held.id);
			if (train && train.speed > 0.005) leftAfterRelease = sample.millis;
		}
		const extendedDone = (stand.get(extended.id) || []).some(s => s.platform && s.to - s.from >= 60_000);
		if (clearedHome && leftAfterRelease && extendedDone) break;
		await sleep(1000);
	}
	const describe = (id: string) => (stand.get(id) || []).map(s => `${s.platform ? "platform" : "line"} ${Math.round((s.to - s.from) / 1000)} s`).join(", ");
	log.push(`extended ${extended.line.layout}号线 ${extended.id}: ${describe(extended.id)}`);
	log.push(`held ${held.line.layout}号线 ${held.id}: ${describe(held.id)}; left ${leftAfterRelease ? Math.round((leftAfterRelease - released) / 1000) + " s after release" : "no"}`);
	log.push(`cleared ${cleared.line.layout}号线 ${cleared.id}: ${clearedHome ? "home after " + Math.round((clearedHome - start) / 1000) + " s" : "not home"}`);
	await testInfo.attach("trains commanded", {body: log.join("\n")});

	expect((stand.get(extended.id) || []).some(s => s.platform && s.to - s.from >= 60_000), "U271: stood at least 60 s longer at a stop").toBe(true);
	expect(heldSince, "U271: the held train stood until let go").toBeGreaterThan(0);
	expect(leftAfterRelease, "U271: the held train left once let go").toBeGreaterThan(0);
	expect(leftAfterRelease - released, "U271: left within a minute of being let go").toBeLessThan(60_000);
	expect(clearedHome, "U272: the train cleared at its next stop went back to its depot").toBeGreaterThan(0);
	expect(errors).toEqual([]);
});
