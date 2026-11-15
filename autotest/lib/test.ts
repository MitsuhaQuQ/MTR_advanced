// The test function every case uses: before each test, no screen is open and the logs and chat are marked; after it,
// a new error or exception in either log fails the test, whatever the test itself checked. Tests run beside others (the
// six lines' observe stage, playwright.config.ts) leave the game's screens alone: run.spec.ts reads its plans off them,
// and an error in the logs then fails whichever tests were running.
import {test as base, expect} from "@playwright/test";
import {client, server, closeAll, chatSince, logsSince, newProblems} from "./game";
import {afterSampling} from "./lines";

export interface Marks {
	/** The chat message number before the test; chatSince(marks.chat) gives what the test caused */
	chat: number,
	clientLog: number,
	serverLog: number,
}

export const test = base.extend<{marks: Marks}>({
	marks: [async ({}, use, testInfo) => {
		// The six lines' stages after setup run beside run.spec.ts while it reads the timetable screens
		const beside = process.env.MTM_FIXTURE_NAME === "lines" && testInfo.project.name !== "setup";
		// The six lines' tests after the sampling: wait for it, then take their turn (the waiting not counted against them)
		const release = beside && testInfo.project.name !== "observe" ? await afterSampling(testInfo) : undefined;
		if (!beside) await closeAll();
		const marks: Marks = {
			chat: (await chatSince(Number.MAX_SAFE_INTEGER)).last,
			clientLog: (await logsSince(client, Number.MAX_SAFE_INTEGER)).last,
			serverLog: (await logsSince(server, Number.MAX_SAFE_INTEGER)).last,
		};
		try {
			await use(marks);
		} finally {
			release?.();
		}
		const problems = [
			...(await newProblems(client, marks.clientLog)).map(e => ({side: "client", ...e})),
			...(await newProblems(server, marks.serverLog)).map(e => ({side: "server", ...e})),
		];
		if (problems.length > 0) {
			await testInfo.attach("log problems", {body: JSON.stringify(problems, null, 2), contentType: "application/json"});
		}
		expect(problems, "no new errors or exceptions in the client or server log").toEqual([]);
		if (!beside) await closeAll();
	}, {auto: true}],
});

export {expect};
