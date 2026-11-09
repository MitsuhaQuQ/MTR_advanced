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

export type MenuItem = [label: string, action: (() => void) | MenuItem[] | null | undefined]

type MenuHandler = { open: (x: number, y: number, items: MenuItem[]) => void; close: () => void }
export const menuHost: { current: MenuHandler | null } = { current: null }

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
  render: (name: string) => onRender(name),
}

const created = createLegacy(env)
/**
 * fn: the old functions by name, both `function f` and `const f = () => …`; v: its variables, read and written live.
 */
export const legacy: { fn: Record<string, any>; v: Record<string, any> } = {
  fn: new Proxy(created.fn, { get: (functions, name: string) => functions[name] ?? created.v[name] }),
  v: created.v,
}

// ---------- Drawing again ----------
let version = 0
const listeners = new Set<() => void>()
let frame = 0
function notify() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    version++
    listeners.forEach(listener => listener())
  })
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
  fn.renderInspector()
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
