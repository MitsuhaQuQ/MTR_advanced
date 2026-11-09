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
// The draft is in core/draft.ts; names and colours in core/model.ts
