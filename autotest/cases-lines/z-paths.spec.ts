// Diagnosis, not a check (2026-11-14): the paths line 6's trains run in the game, for comparing with what the component's
// offline run of the same data takes (SimulateDay -Pmtr.debugPaths=true), as line 6 never uses its turn-back track at 三站
// in the game while the offline run does. Run with the scheduling: MTM_FIXTURE_NAME=lines ui-test.sh run "排班|@paths".
import {test} from "../lib/test";
import {writeFileSync} from "fs";
import {server, sleep} from "../lib/game";
import {lines} from "../lib/lines";

test("6号线列车在游戏里走的路径（诊断） @lines @paths", async ({}, testInfo) => {
	test.setTimeout(15 * 60_000);
	const line6 = lines.find(line => line.layout === 6)!;
	const seen = new Map<string, any>();
	// Every train's path as it changes, for nine minutes
	for (let i = 0; i < 18; i++) {
		const now = await server.call("/trains", {}, {pathZ: String(line6.z)});
		for (const train of now.trains.filter((each: any) => each.path)) {
			const key = `${train.id} ${train.tripPathKey} ${train.path.length}`;
			if (!seen.has(key)) seen.set(key, {at: now.millis, x: train.x, z: train.z, route: train.route, nextStop: train.nextStop, key: train.tripPathKey, path: train.path});
		}
		await sleep(30_000);
	}
	const out = [...seen.entries()].map(([key, value]) => `${key}\n  at ${new Date(value.at).toISOString()} x ${value.x} z ${value.z} route ${value.route} next stop ${value.nextStop}\n` + value.path.map((rail: string, i: number) => `    ${i} ${rail}`).join("\n")).join("\n");
	writeFileSync(`${process.env.MTM_RESULTS || "."}/line6-paths.txt`, out);
	await testInfo.attach("line6 paths", {body: out, contentType: "text/plain"});
});
