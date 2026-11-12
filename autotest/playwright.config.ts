import {defineConfig} from "@playwright/test";

// One game client and one server serve every test, so the tests run one at a time, in file order.
// C:/gtmp/fast/ui-test.sh sets MTM_RESULTS to this run's folder.
const results = process.env.MTM_RESULTS || "test-results";

export default defineConfig({
	testDir: "cases",
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
