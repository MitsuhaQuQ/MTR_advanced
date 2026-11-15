// U479 终点互换运用（高，0.81.0）: two trains of one depot reaching a stop in the other order than their duties have them
// swap the rest of their duties there, so the one there first leaves on the earlier service; the server says so in its
// log. Runs after run.spec, which has the lines running for some minutes and checks they keep to time.
// Covered here: the swaps are told, and for which lines; line 2 or 10 (an empty train out of the depot behind a stopping
// one) among them. Not covered: a restart keeping the swap (the component's DutyRunTest does that).
import {test, expect} from "../lib/test";
import {lines, samplingOver, serverLog} from "../lib/lines";

test("U479 顺序颠倒时互换运用：服务端日志 @lines @U479", async ({}, testInfo) => {
	// The first of the stages after the sampling: waits for it to end
	test.setTimeout(40 * 60_000);
	await samplingOver();
	// The game's own trains: rehearsals before 0.83.4 printed their simulated trains' swaps too, from their own thread
	const swaps = serverLog().split("\n").filter(line => line.includes("Trains out of order for a stop swap the rest of their duties") && !line.includes("MTR-Rehearsal"));
	const lineOf = (line: string) => {
		const siding = (line.match(/siding:(-?\d+)/) || [])[1];
		return lines.find(l => l.sidings.map(String).includes(siding))?.layout;
	};
	const count = new Map<number, number>();
	swaps.forEach(line => { const layout = lineOf(line); if (layout !== undefined) count.set(layout, (count.get(layout) || 0) + 1); });
	await testInfo.attach("swaps", {body: [...count].sort((a, b) => a[0] - b[0]).map(([layout, n]) => `${layout}号线 ${n}`).join("\n") + "\n\n" + swaps.join("\n")});
	expect(swaps.length, "trains swapped duties").toBeGreaterThan(0);
	expect(count.get(2) || count.get(10), "line 2 or 10 swapped").toBeTruthy();
});
