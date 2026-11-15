import {defineConfig, PlaywrightTestProject} from "@playwright/test";

// One game client and one server serve every test. The basic fixture's tests and a world copy's run one at a time, in
// file order. The six lines' (2026-11-15) run in stages, as projects one depending on the next:
// - setup: putting in the timetables through the game's screens, alone;
// - observe: the trains sampled while they run (run.spec.ts, which reads the plans off the timetable screens first) and,
//   at the same time, the checks that only read the web editor, the server's log or its data: several at once;
// - then, one at a time, the tests that change what the trains do (dispatch commands, a timetable changed), and the
//   log read once the trains have run. Each depends on setup only (a project failing skips those depending on it):
//   lib/test.ts has each wait until the sampling is over and then for a lock, so they take turns.
// C:/gtmp/fast/ui-test.sh sets MTM_RESULTS to this run's folder.
const results = process.env.MTM_RESULTS || "test-results";
const lines = process.env.MTM_FIXTURE_NAME === "lines";

/** The six lines' tests run after the sampling, one at a time (lib/test.ts) */
export const AFTER = ["z-swaps", "z-dispatch", "z-adhoc", "z-console", "z-replan"];
const lineProjects: PlaywrightTestProject[] = [
	{name: "setup", testMatch: /\/lines\.spec\.ts$/},
	{name: "observe", testMatch: /\/(run|z-conflicts|z-depot|z-flows|z-headway|z-logs|z-paths|z-rehearsal)\.spec\.ts$/, dependencies: ["setup"]},
	...AFTER.map(file => ({name: file.slice(2), testMatch: new RegExp(`/${file}\\.spec\\.ts$`), dependencies: ["setup"]})),
];

export default defineConfig({
	// The basic fixture's tests, or those of the six track-layout lines (MTM_FIXTURE_NAME=lines)
	// A copy of a player's world (MTM_FIXTURE_NAME=world, with MTM_WORLD): the checks in cases-world
	testDir: lines ? "cases-lines" : process.env.MTM_FIXTURE_NAME === "world" ? "cases-world" : "cases",
	fullyParallel: false,
	// Files of one project run side by side; only the six lines' observe stage has more than one
	// Enough for run.spec.ts, the diagnosis of line 6's paths, the quick checks and the later tests waiting their turn
	workers: lines ? Number(process.env.MTM_WORKERS || 8) : 1,
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
