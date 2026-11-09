/**
 * The shapes of the editor's endpoints, as EditorData / EditorServer write them (see MTR-网页编辑器规格.md, section 7).
 * Only the fields the new page reads so far are typed; the rest stay as they come and pass through untouched.
 */

export type Stop = {
  station: string
  name: string
  platformId: string
  platform: string
  pass: boolean
  passingTrack?: boolean
  boundary?: boolean
  dwellOverride?: number
}

export type Table = {
  name: string
  /** A bit per weekday, Monday first */
  weekdays: number
  marginPercent: number
  /** Departure from the first stop, millis after the operating day starts */
  services: number[]
}

export type Route = {
  id: string
  name: string
  line: string
  /** transport mode | line name: the routes of one line group share it */
  lineKey: string
  serviceType: string
  color: number
  lineColor: number
  circular: boolean
  timetable: boolean
  editorManaged: boolean
  connectingLine: boolean
  revision: string | number
  parts?: Record<string, number>
  editors?: string
  stops: Stop[]
  hasTimes: boolean
  pathFailure?: string
  tables: Table[]
}

export type Siding = { id: string; name: string; maxTrains: number; fleet?: number; routes?: string[]; vehicle?: string }
export type Pool = { key: string; vehicle: string; fleet: number | null; sum: number; sidings: string[] }

export type Depot = {
  id: string
  name: string
  routes: string[]
  sidings: Siding[]
  pools?: Pool[]
  notConfigured?: boolean
  oldLogic?: boolean
  path?: { state: "generating" | "failed" | "stale" | "found"; text: string; failedDuties: number }
  shortfall?: { uncovered: number; replan?: boolean }
}

export type EditorData = {
  dayStartMillis: number
  offsetMillis: number
  canEdit: boolean
  simulation: boolean
  language: string
  regionCount: number
  networkSettings: { minReverseMillis: number; earlyThresholdMillis: number }
  routes: Route[]
  depots: Depot[]
}

/**
 * The draft the page keeps on this computer (api/draft): written whole as the page has it, read back the same. The old
 * page and this one share it, so both must keep every key, including ones this page does not edit yet.
 */
export type Draft = {
  routes: Record<string, { base?: unknown; conflict?: unknown; [part: string]: unknown }>
  created?: unknown[]
  duties?: Record<string, unknown>
  boundaries?: Record<string, unknown>
  depots?: Record<string, unknown>
  depotRules?: Record<string, unknown>
  platformStations?: Record<string, unknown>
  platformSpeeds?: Record<string, unknown>
  platformStops?: Record<string, unknown>
  networkSettings?: unknown
  contingencies?: Record<string, unknown>
  [other: string]: unknown
}

/** How many things the draft changes, counted as the old page counts them (draftCount in editor.js). */
export function draftCount(draft: Draft | null): number {
  if (!draft) return 0
  const keys = (o: unknown) => (o && typeof o === "object" ? Object.keys(o).length : 0)
  return keys(draft.routes) + (draft.created?.length ?? 0) + keys(draft.duties) + keys(draft.boundaries) + keys(draft.depots)
    + keys(draft.depotRules) + keys(draft.platformStations) + keys(draft.platformSpeeds) + keys(draft.platformStops)
    + (draft.networkSettings ? 1 : 0) + keys(draft.contingencies)
}

/** The colour the game stores as an int, as CSS. */
export const colorOf = (value: number) => "#" + (value & 0xffffff).toString(16).padStart(6, "0")
/** Names written as "中文|English" show their first part. */
export const shortName = (name: string) => String(name || "").split("||")[0].split("|")[0]
