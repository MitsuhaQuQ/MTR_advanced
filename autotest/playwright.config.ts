import {defineConfig} from "@playwright/test";

// One game client and one server serve every test, so the tests run one at a time, in file order.
// C:/gtmp/fast/ui-test.sh sets MTM_RESULTS to this run's folder.
const results = process.env.MTM_RESULTS || "test-results";

export default defineConfig({
	// The basic fixture's tests, or those of the six track-layout lines (MTM_FIXTURE_NAME=lines)
	// A copy of a player's world (MTM_FIXTURE_NAME=world, with MTM_WORLD): the checks in cases-world
	testDir: process.env.MTM_FIXTURE_NAME === "lines" ? "cases-lines" : process.env.MTM_FIXTURE_NAME === "world" ? "cases-world" : "cases",
	fullyParallel: false,
	workers: 1,
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
