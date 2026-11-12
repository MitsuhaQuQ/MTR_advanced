// Exports the track network the web editor draws, from the test client that is up (C:/gtmp/fast/ui-test.sh up), as a
// geometry-only sample for the editor's schematic tests: nodes, rails, stations, routes' node paths and the platforms'
// tracks. No players, timetables or duties.
// Usage: node tools/export-network.mjs <output .json> [mode, TRAIN by default]
import {readFileSync, writeFileSync, mkdirSync} from "fs";
import {dirname} from "path";

const [output, mode = "TRAIN"] = process.argv.slice(2);
const endpointFile = process.env.MTM_CLIENT_ENDPOINT || "N:/Games/MultiMC/instances/MTM_AutoTest/.minecraft/testkit/endpoint-client.json";
const {port, token} = JSON.parse(readFileSync(endpointFile, "utf8"));
const driver = await (await fetch(`http://127.0.0.1:${port}/editor-url`, {method: "POST", headers: {"X-TestKit-Token": token}})).json();
const url = new URL(driver.result.url);
const key = new URLSearchParams(url.hash.slice(1)).get("key");
const editor = (path, body) => fetch(new URL(path, url.origin), body === undefined
	? {headers: {"X-MTR-Editor-Key": key}}
	// Writes must come from the page's own origin, as the editor server checks
	: {method: "POST", headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json", "Origin": url.origin}, body: JSON.stringify(body)}).then(r => {
		if (!r.ok) throw new Error(`${path}: ${r.status}`);
		return r.json();
	});

// The first call asks the server for every rail; they come in over the next seconds, so wait until the count settles
let network = await editor("api/network", {mode, fresh: true, version: -1});
for (let last = -1, same = 0; same < 3;) {
	await new Promise(resolve => setTimeout(resolve, 1500));
	network = await editor("api/network", {mode, fresh: false, version: -1});
	const count = (network.rails || []).length;
	same = count === last && count > 0 ? same + 1 : 0;
	last = count;
	console.log(`rails: ${count}`);
}
const data = await editor("api/data");
const platforms = ((data.topology || {}).platforms || []).map(({id, name, station, line, passingTrack}) => ({id, name, station, line, passingTrack}));
const sample = {
	note: `Geometry-only snapshot exported ${new Date().toISOString().slice(0, 10)} by autotest/tools/export-network.mjs`,
	mode: network.mode,
	nodes: network.nodes,
	rails: network.rails,
	stations: network.stations,
	routes: (network.routes || []).map(({id, color, lineKey, name, nodes}) => ({id, color, lineKey, name, nodes})),
	platforms,
};
mkdirSync(dirname(output), {recursive: true});
writeFileSync(output, JSON.stringify(sample));
console.log(`${output}: ${sample.nodes.length} nodes, ${sample.rails.length} rails, ${(sample.stations || []).length} stations, ${sample.routes.length} routes, ${platforms.length} platforms`);
