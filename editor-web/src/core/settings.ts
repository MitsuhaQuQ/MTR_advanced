/**
 * The network settings of the old editor.js (retiring the old code, step 6): the drafted network settings and their
 * parts (logical lines, sections' logical lines, operating lines' tracks, section owners set by hand, wait threshold),
 * a platform's pass speed, stop position and station, a depot's rules and its entry (fleets, routes, vehicles), and
 * pathfinding boundaries. A rail's section comes in as the keys of its rails, worked out by the caller from the network
 * it shows; the functions change only the draft given.
 */
type Data = any
type Draft = any
export type LogicalLine = { id: string; name: string; color: number; connecting?: boolean }

export const draftSettings = (draft: Draft) => draft.networkSettings || {}
export const savedSettings = (data: Data) => (data && data.networkSettings) || {}

/** Rail keys "a,b" of node positions as longs, in the server's order */
export const compareRailKeys = (a: string, b: string) => {
  const [a1, a2] = a.split(",").map(BigInt), [b1, b2] = b.split(",").map(BigInt)
  return a1 < b1 ? -1 : a1 > b1 ? 1 : a2 < b2 ? -1 : a2 > b2 ? 1 : 0
}

/** The logical lines as the draft has them, by ID */
export function logicalLinesShown(data: Data, draft: Draft): Map<string, LogicalLine> {
  const lines = new Map<string, LogicalLine>((savedSettings(data).logicalLines || []).map((line: LogicalLine) => [line.id, Object.assign({}, line)]))
  Object.entries(draftSettings(draft).logicalLines || {}).forEach(([id, line]: [string, any]) => {
    if (line === null) lines.delete(id)
    else lines.set(id, Object.assign({ id }, line))
  })
  return lines
}
export const usesLogicalLines = (data: Data, draft: Draft) => logicalLinesShown(data, draft).size > 0
/** Each section's logical line by a rail of it, as the draft has them */
export const railLinesShown = (data: Data, draft: Draft): Record<string, string | null> => Object.assign({}, savedSettings(data).railLines || {}, draftSettings(draft).railLines || {})

/** The logical line of a section (its rails' keys) as the draft has it, or null; of several rails set, the lowest key counts, as on the server */
export function sectionLogicalLine(data: Data, draft: Draft, sectionKeys: string[]) {
  const effective = railLinesShown(data, draft), lines = logicalLinesShown(data, draft)
  const keys = sectionKeys.filter(key => effective[key] !== null && effective[key] !== undefined && lines.has(effective[key]!)).sort(compareRailKeys)
  return keys.length ? lines.get(effective[keys[0]]!)! : null
}
export const sectionLogicalDrafted = (draft: Draft, sectionKeys: string[]) => sectionKeys.some(key => key in (draftSettings(draft).railLines || {}))

/** Changes one part of the drafted network settings, taking out what is as the game has it, and the settings when nothing is left */
export function setSettingsPart(data: Data, draft: Draft, part: string, change: (drafted: Record<string, any>, saved: Record<string, any>) => void) {
  const saved = part === "logicalLines" ? Object.fromEntries((savedSettings(data).logicalLines || []).map((line: LogicalLine) => [line.id, { name: line.name, color: line.color, connecting: line.connecting }])) : savedSettings(data)[part] || {}
  const drafted = Object.assign({}, draftSettings(draft)[part] || {})
  change(drafted, saved)
  Object.keys(drafted).forEach(key => {
    if (drafted[key] === null ? !(key in saved) : JSON.stringify(drafted[key]) === JSON.stringify(saved[key])) delete drafted[key]
  })
  const settings = Object.assign({}, draft.networkSettings || {})
  if (Object.keys(drafted).length) settings[part] = drafted
  else delete settings[part]
  draft.networkSettings = Object.keys(settings).length ? settings : null
}

/** Puts a section in a logical line, or out of any with null, by the rail chosen */
export function setSectionLogicalLine(data: Data, draft: Draft, key: string, sectionKeys: string[], id: string | null) {
  setSettingsPart(data, draft, "railLines", (drafted, saved) => {
    sectionKeys.forEach(other => {
      if (other === key && id !== null) return
      if (other in saved) drafted[other] = null
      else delete drafted[other]
    })
    if (id !== null) drafted[key] = id
  })
}

export const newLogicalId = () => String(Date.now() * 1000 + Math.floor(Math.random() * 1000))

/** A logical line made or changed */
export const setLogicalLine = (data: Data, draft: Draft, id: string, value: { name: string; color: number; connecting: boolean }) => setSettingsPart(data, draft, "logicalLines", drafted => { drafted[id] = value })

/** Takes a logical line away, with its sections and the operating lines' choices of it */
export function deleteLogicalLine(data: Data, draft: Draft, id: string) {
  setSettingsPart(data, draft, "railLines", (drafted, saved) => {
    Object.entries(railLinesShown(data, draft)).forEach(([key, line]) => {
      if (line !== id) return
      if (key in saved) drafted[key] = null
      else delete drafted[key]
    })
  })
  setSettingsPart(data, draft, "lineTracks", (drafted, saved) => {
    Object.entries(Object.assign({}, saved, drafted)).forEach(([lineKey, ids]: [string, any]) => {
      if (ids && ids.includes(id)) drafted[lineKey] = ids.filter((each: string) => each !== id)
    })
  })
  setSettingsPart(data, draft, "logicalLines", drafted => { drafted[id] = null })
}

/** The logical lines an operating line runs on as the draft has them: ticked, or none for those of its platforms */
export const lineTracksShown = (data: Data, draft: Draft): Record<string, string[]> => Object.assign({}, savedSettings(data).lineTracks || {}, draftSettings(draft).lineTracks || {})
export const setLineTracks = (data: Data, draft: Draft, lineKey: string, ids: string[]) => setSettingsPart(data, draft, "lineTracks", drafted => { drafted[lineKey] = ids })

export const draftOwnerOverrides = (draft: Draft): Record<string, string[] | null> => (draft.networkSettings && draft.networkSettings.ownerOverrides) || {}
export const savedOwnerOverrides = (data: Data): Record<string, string[]> => (data && data.networkSettings && data.networkSettings.ownerOverrides) || {}

/**
 * Who a section is shown as owned by: as the server worked it out (entry), or as the draft sets it by hand. Owners set by
 * hand on several rails of a section are all of theirs, as the server takes them.
 *
 * @return {owners, manual, drafted, auto} where auto is a section the draft gives back to being worked out
 */
export function sectionOwnersShown(data: Data, draft: Draft, sectionKeys: string[], entry: { owners: string[]; manual?: boolean } | null | undefined) {
  const drafted = draftOwnerOverrides(draft), saved = savedOwnerOverrides(data)
  const keys = sectionKeys.filter(key => key in drafted)
  if (!keys.length) return { owners: entry ? entry.owners : [], manual: Boolean(entry && entry.manual), drafted: false, auto: false }
  // What the section will be: the drafted rails as drafted, its other rails set by hand as saved
  const lines = new Set<string>()
  let anySet = false
  sectionKeys.forEach(key => {
    const value = key in drafted ? drafted[key] : saved[key]
    if (value === undefined || value === null) return
    anySet = true
    value.forEach(line => lines.add(line))
  })
  if (!anySet) return { owners: [] as string[], manual: false, drafted: true, auto: true }
  return { owners: [...lines], manual: true, drafted: true, auto: false }
}

/**
 * Sets the owners of a section by hand in the draft: the lines, none to open it to every line, or null to have them
 * worked out again. Kept on the rail chosen, and taken off the section's other rails.
 */
export function setSectionOwners(data: Data, draft: Draft, key: string, sectionKeys: string[], lines: string[] | null) {
  const saved = savedOwnerOverrides(data)
  const overrides: Record<string, string[] | null> = Object.assign({}, draftOwnerOverrides(draft))
  sectionKeys.forEach(other => {
    if (other === key && lines !== null) return
    if (other in saved) overrides[other] = null
    else delete overrides[other]
  })
  if (lines !== null) overrides[key] = lines
  // What only takes back a change not made yet is no change
  Object.keys(overrides).forEach(other => {
    if (overrides[other] === null && !(other in saved)) delete overrides[other]
    else if (overrides[other] !== null && JSON.stringify(overrides[other]) === JSON.stringify(saved[other])) delete overrides[other]
  })
  const settings = Object.assign({}, draft.networkSettings || {})
  if (Object.keys(overrides).length) settings.ownerOverrides = overrides
  else delete settings.ownerOverrides
  draft.networkSettings = Object.keys(settings).length ? settings : null
}

/** The network's wait threshold as drafted, or as the game has it */
export function waitThresholdMillis(data: Data, draft: Draft) {
  const pending = draft.networkSettings && Number.isFinite(draft.networkSettings.waitThresholdMillis) ? draft.networkSettings.waitThresholdMillis : undefined
  const settings = (data && data.networkSettings) || {}
  return pending !== undefined ? pending : Number.isFinite(settings.waitThresholdMillis) ? settings.waitThresholdMillis : 300000
}

/** A station's own wait threshold in milliseconds, or null to take the network's, into the draft */
export function setStationWait(draft: Draft, stationId: string, millis: number | null) {
  draft.networkSettings = draft.networkSettings || {}
  draft.networkSettings.stationWaitThresholds = Object.assign({}, draft.networkSettings.stationWaitThresholds || {})
  draft.networkSettings.stationWaitThresholds[stationId] = millis
}

/** A platform's pass speed (km/h), stop position or station as drafted, or as the game has it */
export const PLATFORM_PARTS = {
  platformSpeeds: { game: (p: any) => p.passSpeed || 80 },
  platformStops: { game: (p: any) => p.stopPosition || 0 },
  platformStations: { game: (p: any) => p.stationSet || "0" },
} as const
export type PlatformPart = keyof typeof PLATFORM_PARTS
export const platformSetting = (draft: Draft, part: PlatformPart, p: any) => (draft[part] && p.id in draft[part] ? draft[part][p.id] : PLATFORM_PARTS[part].game(p))
/** Sets a platform's pass speed, stop position or station in the draft, taken out when it is the game's */
export function setPlatformSetting(draft: Draft, part: PlatformPart, p: any, value: number | string) {
  if (value === PLATFORM_PARTS[part].game(p)) delete draft[part][p.id]
  else draft[part][p.id] = value
}
export const PASS_SPEEDS = [80, 100, 120, 160, 200, 300]
export const STOP_POSITIONS = [0, -1, 2, 5, 10, 20]

/** A depot's rules and named deadhead paths as drafted, or as the game has them (a copy) */
export function rulesOf(draft: Draft, depot: any) {
  const drafted = draft.depotRules && draft.depotRules[depot.id]
  const value = drafted ? drafted.rules : depot.rules
  return JSON.parse(JSON.stringify(value || { rules: [], paths: [] }))
}

/** Whether a vehicle's settings drafted are the game's */
export function sameVehicle(drafted: any, game: any) {
  const set = game && game.set ? game : null
  if (!drafted || !set) return !drafted && !set
  return ["acceleration", "deceleration", "manual", "maxSpeed", "maxManualSpeed", "manualToAutomaticTime"].every(key => (key === "manual" ? drafted.manual === set.manual : Math.abs((drafted[key] || 0) - (set[key] || 0)) < 1e-6))
}

/**
 * A depot's changes as the draft has them, or as the game has the depot when there are none, to change and store again
 * with {@link storeDepotEntry}: the depot panel and the vehicle panel change parts of the same entry
 */
export function depotEntryOf(draft: Draft, depot: any) {
  const drafted = (draft.depots || {})[depot.id]
  if (drafted) {
    const copy = JSON.parse(JSON.stringify(drafted))
    copy.vehicleSettings = copy.vehicleSettings || {}
    copy.serviceTypes = copy.serviceTypes || {}
    copy.poolFleets = copy.poolFleets || {}
    ;(depot.pools || []).forEach((pool: any) => {
      if (!(pool.key in copy.vehicleSettings)) copy.vehicleSettings[pool.key] = pool.vehicleSettings && pool.vehicleSettings.set ? Object.assign({}, pool.vehicleSettings) : null
      if (!(pool.key in copy.poolFleets)) copy.poolFleets[pool.key] = pool.fleet
    })
    return copy
  }
  const sidings: Record<string, any> = {}
  depot.sidings.forEach((siding: any) => { sidings[siding.id] = { fleet: siding.fleet, routes: (siding.routes || []).slice() } })
  const poolFleets: Record<string, any> = {}, vehicleSettings: Record<string, any> = {}
  ;(depot.pools || []).forEach((pool: any) => {
    poolFleets[pool.key] = pool.fleet
    vehicleSettings[pool.key] = pool.vehicleSettings && pool.vehicleSettings.set ? Object.assign({}, pool.vehicleSettings) : null
  })
  return { base: depot.revision, routes: depot.routes.slice(), cycleRoutes: depot.cycleRoutes, sidings, poolFleets, vehicleSettings, serviceTypes: {}, clearBuffers: false, addedBuffers: null }
}

/** Puts a depot's changes in the draft, or takes them out when they change nothing */
export function storeDepotEntry(draft: Draft, depot: any, entry: any, sameAdded?: boolean) {
  const sorted = (list: string[]) => JSON.stringify(list.slice().sort())
  const sidings = entry.sidings || {}
  const same = sorted(entry.routes) === sorted(depot.routes) && entry.cycleRoutes === depot.cycleRoutes
    && depot.sidings.every((siding: any) => sidings[siding.id] && sidings[siding.id].fleet === siding.fleet && sorted(sidings[siding.id].routes) === sorted(siding.routes || []))
    && (depot.pools || []).every((pool: any) => entry.poolFleets[pool.key] === pool.fleet && sameVehicle(entry.vehicleSettings[pool.key], pool.vehicleSettings)) && !Object.keys(entry.serviceTypes || {}).length && !entry.clearBuffers && (sameAdded === undefined ? !entry.addedBuffers : sameAdded)
  if (same) delete draft.depots[depot.id]
  else draft.depots[depot.id] = entry
}

/** Sets or clears a pathfinding boundary on a rail in the draft */
export function setBoundary(draft: Draft, key: string, boundary: boolean, wasBoundary: boolean) {
  if (boundary === wasBoundary) delete draft.boundaries[key]
  else draft.boundaries[key] = boundary
}
