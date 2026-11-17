// U564 网页线路监控、咽喉信号按进路显示（高，0.99.0）, over the six lines' fixture with its trains running by duties.
// Covered here: a logical line set through the editor's API (every rail line 10's routes run on), and the dispatch
// layer's 线路 view of it: the logical line's physical track drawn, the trains' number tags on it and the rails taken
// coloured, without page errors (screenshot for judging, AI 判); and at a station throat turnout whose routes are locked,
// what a signal there shows, sampled for a while: stop while no route is locked through it, and something other than
// stop at least once while one is.
// Not covered: the colours of locked routes on the web view checked against the game, signal blocks put down there.
import {test, expect} from "../lib/test";
import {client, player, serverCommand, server, sleep, waitFor} from "../lib/game";
import {openNextEditor} from "../lib/editor";

/** The editor's address and key, for calling its API as the page does (writes need the page's Origin) */
async function editorApi() {
	const {url} = await client.call("/editor-url");
	const address = new URL(url);
	const key = address.hash.replace(/^#key=/, "");
	return async (path: string, body: unknown) => {
		const response = await fetch(`${address.origin}/api/${path}`, {method: "POST", headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json", "Origin": address.origin}, body: JSON.stringify(body)});
		expect(response.ok, `api/${path} answered ${response.status}`).toBeTruthy();
		return response.json();
	};
}

test("U564 网页线路监控：按逻辑线路的物理轨道、车次框和占用 @lines @U564", async ({page}, testInfo) => {
	test.setTimeout(600_000);
	// A logical line owning every rail line 10's routes run on, set through the editor's API as the ownership layer does
	const api = await editorApi();
	const network = await api("network", {mode: "TRAIN", fresh: true, version: -1});
	const railLines: Record<string, string> = {};
	const LINE_ID = "990010";
	for (const route of network.routes || []) {
		if (!/^10号线/.test(String(route.name))) continue;
		for (let i = 1; i < route.nodes.length; i++) {
			const a = network.nodes[route.nodes[i - 1]], b = network.nodes[route.nodes[i]];
			if (a && b) railLines[`${a[2]},${b[2]}`] = LINE_ID;
		}
	}
	expect(Object.keys(railLines).length, "line 10's rails").toBeGreaterThan(5);
	const applied = await api("apply", {operations: [{type: "networkSettings", logicalLines: {[LINE_ID]: {name: "测试逻辑线10", color: 0x2563eb}}, railLines}]});
	await testInfo.attach("applied", {body: JSON.stringify(applied).slice(0, 500)});

	const {errors} = await openNextEditor(page, "dispatch");
	await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "线路"}).click();
	const select = page.getByRole("combobox", {name: "逻辑线路"});
	await expect(select.locator("option", {hasText: "测试逻辑线10"})).toBeAttached({timeout: 60_000});
	await select.selectOption({label: "测试逻辑线10"});
	const monitor = page.locator("[data-line-monitor]");
	await expect(monitor.locator("[data-rail]").first(), "the logical line's rails drawn").toBeAttached({timeout: 30_000});
	await expect(monitor.locator("[data-train]").first(), "a train on the line").toBeVisible({timeout: 120_000});
	await expect(monitor.locator('[data-state="taken"]').first(), "the rails trains are on coloured as taken").toBeAttached({timeout: 10_000});
	const counts = {trains: await monitor.locator("[data-train]").count(), taken: await monitor.locator('[data-state="taken"]').count(), locked: await monitor.locator('[data-state="locked"]').count(), rails: await monitor.locator("[data-rail]").count()};
	await testInfo.attach("line monitor", {body: JSON.stringify(counts)});
	await page.waitForTimeout(2500);
	await page.screenshot({path: `${process.env.MTM_RESULTS || "test-results"}/screenshots/U564-line-monitor.png`, timeout: 60_000});
	expect(errors).toEqual([]);
});

test("U564 咽喉道岔前的信号：没排进路停车，进路锁上后放行 @lines @U564", async ({}, testInfo) => {
	test.setTimeout(420_000);
	const throats = await waitFor("station throat turnouts on the trains' paths", async () => {
		const result = await server.call("/throats");
		return result.turnouts.length > 0 ? result : null;
	}, 120_000, 2000);
	// The turnout locked most often in a first look, so a route is likely through it while sampling
	const lockedCount = new Map<string, number>();
	for (let i = 0; i < 10; i++) {
		(await server.call("/throats")).locked.forEach((route: any[]) => {
			const key = route.slice(0, 3).join(",");
			lockedCount.set(key, (lockedCount.get(key) || 0) + 1);
		});
		await sleep(1000);
	}
	const chosen: number[] = lockedCount.size ? [...lockedCount.entries()].sort((a, b) => b[1] - a[1])[0][0].split(",").map(Number) : throats.turnouts[0];
	const name = await player();
	await serverCommand(`tp ${name} ${chosen[0]} ${chosen[1] + 20} ${chosen[2]}`);
	await sleep(3000);
	const samples: {locked: number, aspects: number[]}[] = [];
	let stopWhileFree = false, clearWhileLocked = false;
	const end = Date.now() + 300_000;
	while (Date.now() < end && !(stopWhileFree && clearWhileLocked)) {
		const result = await client.call("/signal-aspect", {x: chosen[0], y: chosen[1], z: chosen[2]});
		const aspects = result.ways.map((way: any) => way.aspect);
		samples.push({locked: result.locked, aspects});
		if (result.managed && result.locked === 0 && aspects.includes(1)) stopWhileFree = true;
		if (result.managed && result.locked > 0 && aspects.some((aspect: number) => aspect === 0 || aspect === 2)) clearWhileLocked = true;
		await sleep(1000);
	}
	await testInfo.attach("signal samples", {body: JSON.stringify({turnout: chosen, samples: samples.slice(-60)})});
	expect(samples.some(s => s.aspects.length > 0), "the client knows the turnout's rails").toBe(true);
	expect(stopWhileFree, "stop while no route is locked through the turnout").toBe(true);
	expect(clearWhileLocked, "not stop at least once while a route is locked through it").toBe(true);
});
