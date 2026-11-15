import {defineConfig, PlaywrightTestProject} from "@playwright/test";

// One game client and one server serve every test. The basic fixture's tests and a world copy's run one at a time, in
// file order. The six lines' (2026-11-15) run in stages, as projects one depending on the next:
// - setup: putting in the timetables through the game's screens, alone;
// - observe: the trains sampled while they run (run.spec.ts, which reads the plans off the timetable screens first) and,
//   at the same time, the checks that only read the web editor, the server's log or its data: several at once;
// - then, one after another, the tests that change what the trains do (dispatch commands, a timetable changed), and
//   the log read once the trains have run. They depend on setup only, not on observe: a stage that fails skips the
//   stages depending on it, and run.spec.ts fails on known lateness (line 2's express). The first of them waits until
//   the sampling is over (lib/lines.ts samplingOver).
// C:/gtmp/fast/ui-test.sh sets MTM_RESULTS to this run's folder.
const results = process.env.MTM_RESULTS || "test-results";
const lines = process.env.MTM_FIXTURE_NAME === "lines";

/** The six lines' tests changing what the trains do, in this order, each after the one before */
const AFTER = ["z-swaps", "z-dispatch", "z-adhoc", "z-console", "z-replan"];
const lineProjects: PlaywrightTestProject[] = [
	{name: "setup", testMatch: /\/lines\.spec\.ts$/},
	{name: "observe", testMatch: /\/(run|z-conflicts|z-depot|z-flows|z-headway|z-logs|z-paths|z-rehearsal)\.spec\.ts$/, dependencies: ["setup"]},
	...AFTER.map((file, i) => ({name: file.slice(2), testMatch: new RegExp(`/${file}\\.spec\\.ts$`), dependencies: [i ? AFTER[i - 1].slice(2) : "setup"]})),
];

export default defineConfig({
	// The basic fixture's tests, or those of the six track-layout lines (MTM_FIXTURE_NAME=lines)
	// A copy of a player's world (MTM_FIXTURE_NAME=world, with MTM_WORLD): the checks in cases-world
	testDir: lines ? "cases-lines" : process.env.MTM_FIXTURE_NAME === "world" ? "cases-world" : "cases",
	fullyParallel: false,
	// Files of one project run side by side; only the six lines' observe stage has more than one
	workers: lines ? Number(process.env.MTM_WORKERS || 4) : 1,
	projects: lines ? lineProjects : undefined,
	timeout: 120_000,
	expect: {timeout: 15_000},
	retries: 0,
	outputDir: `${results}/artifacts`,
	reporter: [["list"], ["html", {outputFolder: `${results}/report`, open: "never"}], ["json", {outputFile: `${results}/results.json`}]],
	use: {
		// The web editor is opened in the Chrome installed on this machine, so no browser has to be downloaded
		channel: "chrome",
		headless: true,
		viewport: {width: 1600, height: 900},
		screenshot: "only-on-failure",
		trace: "off",
	},
});
