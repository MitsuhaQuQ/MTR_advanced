/**
 * The old editor's code (engine.generated.js) running in the new page, and how the two are tied together:
 * - the game's data goes in through setData, a copy of what the store read;
 * - the draft goes in whenever the store's changes (undo, the review panel, applying, the old page in another tab), and
 *   comes back to the store as one step of undo whenever the old code changes it (it then calls renderDraftState);
 * - the old code's renderAll draws the old views it still owns and tells React to draw again;
 * - its menus are drawn by React (Menu.tsx) from its [label, action] items.
 */
import "./stage"
import { useSyncExternalStore } from "react"
import { key } from "../api/client"
import { createLegacy } from "./engine.generated.js"
import { normalizeDraft } from "../core/draft"
import * as Services from "../core/services"
import { dispatchRules, dutyRules, settingsRules, timetableRules } from "./retired"
import { dialogRules } from "./dialogs"

export type MenuItem = [label: string, action: (() => void) | MenuItem[] | null | undefined]

type MenuHandler = { open: (x: number, y: number, items: MenuItem[]) => void; close: () => void }
export const menuHost: { current: MenuHandler | null } = { current: null }

/** The track diagram to show: of a route, or of a line group (views/TrackDiagram.tsx) */
export type DiagramTarget = { kind: "route"; routeId: string } | { kind: "line"; lineKey: string }
export const diagramHost: { current: ((target: DiagramTarget) => void) | null } = { current: null }

/** The stops of a route to edit, at a stop or not, or a new line group to make (views/StopsEditor.tsx) */
export type StopsTarget = { kind: "route"; routeId: string; stop: number } | { kind: "new" } | { kind: "type"; lineKey: string; baseId: string }
export const stopsHost: { current: ((target: StopsTarget) => void) | null } = { current: null }

/** One of the line layer's other panels (views/LinePanels.tsx): speed limits, through services, contingency plans */
export type PanelTarget = { kind: "speed"; routeIds: string[] } | { kind: "through"; routeId: string } | { kind: "contingency"; anchor: string } | { kind: "table"; routeId: string; index: number } | { kind: "depot"; depotId: string } | { kind: "rules"; depotId: string; editing: any; opened: number } | { kind: "vehicle"; depotId: string; poolKey: string } | { kind: "groups"; depotId: string } | { kind: "release"; depotId: string }
  // The old page's last small dialogs (views/AskPanels.tsx, retiring the old code step 8)
  | ({ kind: "ask" } & AskTarget) | { kind: "serviceOrder"; serviceId: string; order: "deploy" | "return"; stop: number } | { kind: "logicalLine"; lineId: string | null; then?: (id: string) => void }
  | { kind: "owners"; index: number } | { kind: "lineTracks"; lineKey: string } | { kind: "finishPicking" }
/** A question in a side panel: things to know, perhaps one value, and what the answer does (it may say what is wrong); without go, only told */
export type AskTarget = {
  title: string; text: string[]; list?: string[]; hints?: string[]; ok: string; danger?: boolean
  input?: { label: string; value: string; unit?: string; placeholder?: string; numeric?: boolean }
  go?: (value: string) => string | void
}
export const panelHost: { current: ((target: PanelTarget) => void) | null } = { current: null }

const api = (path: string) => new URL("/" + path.replace(/^\/+/, ""), location.origin).toString()

const env = {
  MTREditor: { key, ws: "timetable", part: "", open() {}, inTimetable: () => true },
  openMenu: (x: number, y: number, items: MenuItem[]) => menuHost.current?.open(x, y, items),
  openMenuAt: (e: MouseEvent, items: MenuItem[]) => {
    if (e.clientX || e.clientY) menuHost.current?.open(e.clientX, e.clientY, items)
    else {
      const box = (e.target as HTMLElement).getBoundingClientRect()
      menuHost.current?.open(box.left + 8, box.bottom + 2, items)
    }
  },
  closeMenu: () => menuHost.current?.close(),
  buildMenu: () => document.createElement("div"),
  async fetchJson(path: string) {
    const response = await fetch(api(path), { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" })
    if (!response.ok) throw new Error(String(response.status))
    return response.json()
  },
  async sendJson(path: string, method: string, body: unknown) {
    // The draft is saved by the page's store, which has it from renderDraftState
    if (path === "api/draft") return { ok: true }
    const response = await fetch(api(path), { method, headers: { "X-MTR-Editor-Key": key, "Content-Type": "application/json" }, body: JSON.stringify(body), cache: "no-store" })
    if (!response.ok) throw new Error(String(response.status))
    return response.json()
  },
  setTreeOpen: () => {},
  // The old code's track diagram dialogs: the new page's schematic instead
  routeDiagramDialog: (r: { id: string }) => diagramHost.current?.({ kind: "route", routeId: r.id }),
  lineDiagramDialog: (line: { key: string }) => diagramHost.current?.({ kind: "line", lineKey: line.key }),
  // The old stops and new line dialogs: the new page's stops editor instead (a station's own diagram is views/StationTracks.tsx)
  stopsDialog: (r: { id: string }, stop?: number) => stopsHost.current?.({ kind: "route", routeId: r.id, stop: stop ?? -1 }),
  newLineDialog: () => stopsHost.current?.({ kind: "new" }),
  newTypeDialog: (lineKey: string, base?: { id: string }) => stopsHost.current?.({ kind: "type", lineKey, baseId: base ? base.id : "" }),
  speedLimitDialog: (routes: { id: string }[]) => panelHost.current?.({ kind: "speed", routeIds: routes.map(r => r.id) }),
  throughDialog: (r?: { id: string } | null) => panelHost.current?.({ kind: "through", routeId: r ? r.id : "" }),
  contingencyDialog: (system: { anchor: string }) => panelHost.current?.({ kind: "contingency", anchor: system.anchor }),
  tableDialog: (r: { id: string }, index: number) => panelHost.current?.({ kind: "table", routeId: r.id, index }),
  depotDialog: (depot: { id: string }) => panelHost.current?.({ kind: "depot", depotId: depot.id }),
  depotRulesDialog: (depot: { id: string }, editing?: unknown) => panelHost.current?.({ kind: "rules", depotId: depot.id, editing: editing || null, opened: Date.now() }),
  vehicleDialog: (depot: { id: string }, pool: { key: string }) => panelHost.current?.({ kind: "vehicle", depotId: depot.id, poolKey: pool.key }),
  dutyGroupsDialog: (depot: { id: string }) => panelHost.current?.({ kind: "groups", depotId: depot.id }),
  releasePlanDialog: (depot: { id: string }) => panelHost.current?.({ kind: "release", depotId: depot.id }),
  // Retiring the old code, step 3: the old code's rows and services made by the new page's (core/services.ts)
  buildStations: Services.buildStations,
  buildServices: () => {
    const { fn, v } = legacy
    v.data.routes.forEach((r: any) => { r.services = Services.routeServices(r, fn.timetableOf(r), fn.timesSource(r), v.state.weekday, v.data.dayStartMillis) })
  },
  plansFor: Services.plansFor,
  toPlans: Services.toPlans,
  gameSource: Services.gameSource,
  findService: (id: string) => Services.findService(legacy.v.data.routeById, id),
  timesOf: Services.timesOf,
  currentLine: () => Services.currentLine(legacy.v.data, legacy.v.state.line),
  // Step 4: the timetable rules
  ...timetableRules(() => legacy),
  ...dutyRules(() => legacy),
  ...settingsRules(() => legacy),
  ...dispatchRules(() => legacy),
  ...dialogRules(() => legacy, target => panelHost.current?.(target)),
  render: (name: string) => onRender(name),
}

const created = createLegacy(env)
/**
 * fn: the old functions by name, both `function f` and `const f = () => …`; v: its variables, read and written live.
 */
export const legacy: { fn: Record<string, any>; v: Record<string, any> } = {
  fn: new Proxy(created.fn, { get: (functions, name: string) => functions[name] ?? created.v[name] ?? (env as Record<string, any>)[name] }),
  v: created.v,
}

// The running diagram the old way (views/RunningDiagram.tsx draws it otherwise), as the viewer last chose
try { legacy.v.oldDiagram = localStorage.getItem("mtr-next-old-diagram") === "1" } catch { /* private window */ }

// ---------- Drawing again ----------
let version = 0
const listeners = new Set<() => void>()
// Batched to once per turn of the event loop; not by animation frame, which a hidden tab doesn't get
let pending = false
function notify() {
  if (pending) return
  pending = true
  setTimeout(() => {
    pending = false
    version++
    listeners.forEach(listener => listener())
  })
}
/** Tells the components to draw again now, not on the next turn (for tests, inside act). */
export function flushLegacy() {
  pending = false
  version++
  listeners.forEach(listener => listener())
}
/** Re-renders a component whenever the old code draws again. */
export const useLegacy = () => useSyncExternalStore(listener => { listeners.add(listener); return () => listeners.delete(listener) }, () => version)

/** The old views drawn in the old page's renderAll, other than those the new page draws itself. */
export function renderLegacy() {
  const { fn, v } = legacy
  const data = v.data, sim = v.sim, state = v.state
  if (data) {
    if (!data.simulation && sim.on) fn.setSimulation(false)
    if (sim.on && !sim.timer && (sim.line !== state.line || sim.weekday !== state.weekday)) fn.scheduleSimulation()
  }
  fn.watchNetwork()
  fn.drawNetwork()
  fn.draw()
  fn.renderTable()
  fn.renderSimStatus()
}

function onRender(name: string) {
  if (name === "renderAll") renderLegacy()
  checkDraft()
  notify()
}

// ---------- The draft both ways ----------
let pushedJson = ""
let draftListener: ((draft: any) => void) | null = null
export const onLegacyDraft = (listener: (draft: any) => void) => { draftListener = listener }

/** The store's draft, given to the old code, which then works out its services again. */
export function pushDraft(given: any) {
  // With every part present, as the old code's loadDraft leaves it
  const draft = normalizeDraft(given)
  const json = JSON.stringify(draft)
  if (json === pushedJson) return
  pushedJson = json
  legacy.v.draft = JSON.parse(json)
  legacy.v.coverage = null
  legacy.v.terminal = null
  if (legacy.v.data) {
    legacy.fn.buildServices()
    if (legacy.v.state.selected && !legacy.fn.findService(legacy.v.state.selected)) legacy.v.state.selected = null
    renderLegacy()
  }
  notify()
}

/**
 * Whether the old code changed the draft; if so, the store takes it. Looked at once the old code is done with what it
 * is doing, so one action changing several routes (a dialog applying to the whole line) is one step of undo.
 */
let checking = false
function checkDraft() {
  if (checking) return
  checking = true
  setTimeout(() => {
    checking = false
    const json = JSON.stringify(legacy.v.draft)
    if (json === pushedJson) return
    pushedJson = json
    draftListener?.(JSON.parse(json))
  })
}

// ---------- The game's data ----------
let pickedToday = false
/** The game's data, as a copy: the old code adds to it (lines, rows, services). */
export function pushData(data: unknown) {
  const { fn, v } = legacy
  fn.setData(JSON.parse(JSON.stringify(data)))
  if (!pickedToday) {
    // Today in the operating day, and the view starting just before the first trains (the old page's start-up)
    pickedToday = true
    const d = v.data
    const today = Math.floor((Date.now() - d.dayStartMillis) / v.DAY)
    v.state.weekday = fn.mod(today + v.FIRST_DAY_WEEKDAY, 7)
    fn.buildServices()
    const firsts = d.routes.flatMap((r: any) => r.services.map((s: any) => s.start))
    v.state.viewStart = firsts.length ? Math.max(-1800000, Math.min(...firsts) - 1800000) : 2 * 3600000
    renderLegacy()
  }
  checkDraft()
  notify()
}
