/**
 * Generates src/legacy/engine.generated.js and public/legacy.css from the old editor (editor.js, editor.css), so the new
 * page runs the old page's own code while the migration lasts and both pages always behave the same: stations,
 * services, times, duties, timetable editing, the running diagram's drawing and hit tests, the summary table and the
 * inspector with their event handlers, and the old dialogs.
 *
 * editor.js is one function, (() => { … })(). Its body is taken whole, up to its start-up code, inside a factory
 * createLegacy(env), with these differences:
 * - menus (openMenu …) are drawn by React from the old [label, action] items;
 * - endpoints (fetchJson, sendJson) go to /api/ at the server's root, and saving the draft goes to the page's store;
 * - the render* functions for panels the new page draws itself tell React to draw again instead;
 * - the start-up code (loading the draft, polling, picking today) is left out: the page's store does it.
 * Event handlers are registered on the elements the new page gives the old ids to (stage.ts); those of the old page's
 * buttons the new page doesn't have land on stand-in elements and never fire.
 *
 * editor.css is wrapped in `.legacy { … }` (CSS nesting), so its rules apply only inside the new page's .legacy boxes.
 *
 * Run by build.sh and `npm run gen`; the output is committed so `npm run dev` works without it. At step 7, when the old
 * page goes, these files become source and are split into modules.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { declarations } from "./extract.mjs"

const here = path.dirname(fileURLToPath(import.meta.url))
const EDITOR = path.resolve(here, "../../common/src/main/resources/assets/mtr/editor")
const OUT = path.resolve(here, "../src/legacy/engine.generated.js")
const CSS_OUT = path.resolve(here, "../public/legacy.css")

/** Taken from env instead of editor.js */
const FROM_ENV = ["openMenu", "openMenuAt", "closeMenu", "buildMenu", "fetchJson", "sendJson", "setTreeOpen",
  // The track diagrams of a route and of a line group: the new page's schematic (views/TrackDiagram.tsx)
  "routeDiagramDialog", "lineDiagramDialog",
  // Retiring the old code, step 3: the rows and services are the new page's (src/core/services.ts)
  "buildStations", "buildServices", "plansFor", "toPlans", "gameSource", "findService", "timesOf", "currentLine",
  // Step 4: the timetable rules (src/core/timetable.ts, through src/legacy/retired.ts)
  "draftOf", "draftEntry", "tidyDraft", "gameTimetable", "fromGame", "normalize", "timetableOf", "editTimetable", "setStopException",
  "syncExceptions", "retimeService", "setSuspended", "removeService", "setServiceOrder", "changeTime", "laterServices", "moveServices",
  "shiftLaterDialog", "parseShift", "respaceRange", "setLink", "clearTable", "servicesToAdd", "renumberTimetable", "renumberLinksInto",
  "inRange", "rangeServices", "chooseRange", "toggleChosen", "chooseLater", "servicesToMove", "timesSource", "sendTimes", "draftTimes", "requestedTimes", "timesTimer",
  // Step 5: the duty rules (src/core/duties.ts, through src/legacy/retired.ts)
  "canonDuties", "dutiesOf", "routeIsLoop", "dutyOn", "segmentKey", "startOf", "newDutyId", "dutyIdOf", "findDutySlot", "dutyNumber", "relinkOvernight",
  "poolSidings", "editDuties", "dayGroup", "dutyForEditing", "segmentService", "lastStopOf", "segmentStart", "segmentEnd", "coverageNow", "hasNoTrain",
  "terminalNow", "dutyAssignments", "dutyProblems", "nextSlotOf", "dutyEndPlatform", "dutyStartPlatform", "minReverseMillis", "turnTimeOf", "stopLabel",
  "stopHangers", "renumberDuties", "sendStopList",
  // Step 6: the network settings (src/core/settings.ts, through src/legacy/retired.ts)
  "draftSettings", "savedSettings", "compareRailKeys", "logicalLinesShown", "usesLogicalLines", "railLinesShown", "railLogicalLine", "railLogicalDrafted",
  "draftSettingsPart", "setRailLogicalLine", "newLogicalId", "deleteLogicalLine", "draftOwnerOverrides", "savedOwnerOverrides", "railOwnersShown",
  "setOwnerOverride", "waitThresholdMillis", "platformSpeedItems", "platformStopItems", "platformStationItems", "rulesOf", "sameVehicle", "depotEntryOf",
  "storeDepotEntry", "setBoundary",
  // Step 7: dispatch and simulation (src/core/dispatch.ts, through src/legacy/retired.ts)
  "setSimulation", "scheduleSimulation", "simulate", "pollSimulation", "setSimResult", "simFromNow", "simLate", "simTooltip", "dutyCheckText", "loadConsole",
  "toggleConsole", "consoleTrains", "consoleTrainName", "consoleTrainState", "consoleCommand", "isLoop", "loopEntryStop", "loopRuns",
  // Step 8: the last small dialogs as side panels (src/views/AskPanels.tsx, through src/legacy/dialogs.ts)
  "generateDuties", "replanDialog", "placeTrainsDialog", "deleteTypeDialog", "stationWaitDialog", "logicalDraftDialog", "serviceOrderDialog",
  "logicalLineDialog", "ownersDialog", "lineTracksDialog", "finishPicking", "deadheadMenu",
  // The dialogs the new page has its own panels for (views/StopsEditor.tsx, LinePanels.tsx, DepotPanel.tsx, DepotExtras.tsx,
  // StationTracks.tsx): nothing falls back on the old ones any more, so they are left out
  "stopsDialog", "newLineDialog", "newTypeDialog", "speedLimitDialog", "throughDialog", "contingencyDialog", "tableDialog", "depotDialog",
  "depotRulesDialog", "vehicleDialog", "dutyGroupsDialog", "releasePlanDialog",
  // A station's turn platforms (src/core/stationLayout.ts)
  "turnOf", "toggleTurnPlatform"]
/**
 * Called as env's, but the old function stays as old<Name> for the new page to fall back on: the stops editor and the new
 * line editor (views/StopsEditor.tsx) open the old stops dialog for a station's own track diagram and turn platforms
 */
const WRAPPED = []
const oldName = name => "old" + name[0].toUpperCase() + name.slice(1)
/**
 * Run as before, then tell the page (env.render(name)): the old running diagram's draw(), which many old functions call
 * on their own, so the new page's running diagram (views/RunningDiagram.tsx) draws again with it
 */
const NOTIFY = ["draw"]
/** Panels the new page draws itself: the old function becomes a call to env.render(name, args) */
const RENDERS = ["renderAll", "renderTree", "renderInspector", "renderViews", "renderBanner", "renderStatus", "renderSyncButton", "renderDraftState"]

// Line ends as checked out on Windows
const src = fs.readFileSync(path.join(EDITOR, "editor.js"), "utf8").replace(/\r\n/g, "\n")
const bodyStart = src.indexOf("(() => {\n") + "(() => {\n".length
const bodyEnd = src.indexOf("\t// ---------- Start ----------")
if (bodyStart < 9 || bodyEnd < 0) throw new Error("editor.js is no longer (() => { … // ---------- Start ---------- … })(): update gen-legacy.mjs")

// The start-up code is left out, but constants it declares that the rest uses (nowDay, for the line of the time now)
// are kept; pickToday starts a timer, which the page's store does instead
const startDecls = declarations(src).filter(d => d.start >= bodyEnd && d.kind !== "function" && !d.names.includes("pickToday"))
const decls = declarations(src).filter(d => d.start >= bodyStart && d.end <= bodyEnd).concat(startDecls)
const named = name => decls.find(d => d.names.includes(name))
const missing = [...FROM_ENV, ...WRAPPED, ...NOTIFY, ...RENDERS].filter(name => !named(name))
if (missing.length) throw new Error("editor.js no longer has " + missing.join(", ") + ": update gen-legacy.mjs")

// Replacements, by position in the body, applied from the end so earlier positions hold
const replacements = []
for (const name of FROM_ENV) replacements.push({ ...named(name), text: `/* ${name}: from env */` })
for (const name of WRAPPED) replacements.push({ ...named(name), text: named(name).text.replace(`function ${name}(`, `function ${oldName(name)}(`) })
for (const name of NOTIFY) replacements.push({ ...named(name), text: named(name).text.replace(`function ${name}(`, `function ${name}Before(`) + `
	function ${name}(...args) { const result = ${name}Before(...args); env.render(${JSON.stringify(name)}, args); return result }` })
for (const name of RENDERS) replacements.push({ ...named(name), text: `function ${name}(...args) { return env.render(${JSON.stringify(name)}, args) }` })
replacements.sort((a, b) => b.start - a.start)
let body = src.slice(bodyStart, bodyEnd)
for (const r of replacements) body = body.slice(0, r.start - bodyStart) + r.text + body.slice(r.end - bodyStart)
body += startDecls.map(d => "\t" + d.text + "\n").join("")

const functions = [], variables = []
for (const decl of decls) {
  if (decl.names.some(name => FROM_ENV.includes(name))) continue
  if (decl.kind === "function") functions.push(...decl.names.map(name => (WRAPPED.includes(name) ? oldName(name) : name)))
  else variables.push(...decl.names.map(name => [name, decl.kind]))
}
const accessors = variables.map(([name, kind]) => kind === "let"
  ? `\t\tget ${name}() { return ${name} }, set ${name}(value) { ${name} = value },`
  : `\t\tget ${name}() { return ${name} },`).join("\n")

const out = `// GENERATED by editor-web/scripts/gen-legacy.mjs from common/src/main/resources/assets/mtr/editor/editor.js.
// Do not edit: change editor.js (both pages run it) or the generator, then run \`npm run gen\`.
/* eslint-disable */
// @ts-nocheck
export function createLegacy(env) {
\tconst { MTREditor, ${[...FROM_ENV, ...WRAPPED].join(", ")} } = env;
${body}
\treturn {
\t\tfn: { ${functions.join(", ")} },
\t\tv: {
${accessors}
\t\t},
\t};
}
`
fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, out)

// The old page's elements, without its scripts: the new page keeps them on a hidden stage and moves the ones it shows
const html = fs.readFileSync(path.join(EDITOR, "index.html"), "utf8").replace(/\r\n/g, "\n")
const markup = html.slice(html.indexOf("<body>") + "<body>".length, html.indexOf("</body>")).replace(/<script[^>]*><\/script>\s*/g, "")
fs.writeFileSync(path.resolve(here, "../src/legacy/markup.generated.ts"),
  `// GENERATED by editor-web/scripts/gen-legacy.mjs from the old editor's index.html: its elements, without scripts.\nexport const LEGACY_MARKUP = ${JSON.stringify(markup)}\n`)

const css = fs.readFileSync(path.join(EDITOR, "editor.css"), "utf8").replace(/\r\n/g, "\n")
fs.mkdirSync(path.dirname(CSS_OUT), { recursive: true })
fs.writeFileSync(CSS_OUT, `/* GENERATED by editor-web/scripts/gen-legacy.mjs from editor.css: the old page's styles, only inside .legacy boxes. */\n.legacy {\n${css}\n}\n`)
console.log(`legacy engine: ${functions.length} functions, ${variables.length} variables from editor.js`)

// The media library is the new page's own (views/MediaLibrary.tsx); the old page still runs media.js itself.
