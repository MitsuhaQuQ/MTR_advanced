/**
 * The data the editor shows, made from api/data and the draft (retiring the old code, step 3: the old setData's line
 * groups, revisits, buildStations, toPlans, plansFor, buildServices, findService, timesOf, currentLine). The legacy engine
 * calls these now (gen-legacy's FROM_ENV), so the page has one set of lines, rows and services, and the old rules not yet
 * moved over change the same objects. test/services.test.ts runs the old ones beside these.
 */
import { DAY, mod } from "./model"

export const SECTION_GAP = 1.5

export type Plan = { arrival: number; departure: number; overtake: boolean; passes: boolean; tooFast: boolean }
/** Where a route's times come from: per table, its stops' times, and each service's own where it has exceptions */
export type TimesSource = { times: number[][]; exceptions: Map<number, number[][]> }[]

/** The routes in line groups, by name */
export function groupLines(routes: any[]) {
  const lines = new Map<string, any>()
  routes.forEach(r => {
    if (!lines.has(r.lineKey)) lines.set(r.lineKey, { key: r.lineKey, name: r.line, color: r.lineColor, routes: [] })
    lines.get(r.lineKey).routes.push(r)
  })
  return [...lines.values()].sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * Where a route comes back to a station it has stopped at: going back the way it came (retrace), or coming round to it
 * from elsewhere, on the platform it stopped at before or on another.
 */
export function revisits(stations: string[], platforms: string[]) {
  const first = new Map<string, number>()
  return stations.map((id, i) => {
    if (!first.has(id)) {
      first.set(id, i)
      return null
    }
    const earlier = first.get(id)!
    return { earlier, retrace: i > 0 && (stations[earlier - 1] === stations[i - 1] || stations[earlier + 1] === stations[i - 1]), samePlatform: platforms[earlier] === platforms[i] }
  })
}

type Row = { id: string; name: string; base: Row }

/**
 * The stations of a line down the side of its diagram, in sections (see the old buildStations): the longest route down
 * the first section; a branch, another way between two stations, a loop and a Q each in the way described there. Sets
 * line.rows, and each route's rows (where it arrives at each stop) and leaveRows (where it leaves from).
 */
export function buildStations(line: any) {
  const stationOf = (stop: any) => stop.station || "?" + stop.name
  const sections: Row[][] = []
  const rowsOf = (id: string) => sections.flatMap((section, k) => section.flatMap((row, j) => (row.id === id ? [[k, j]] : [])))
  const newRow = (id: string, name: string, base?: Row): Row => {
    const row = { id, name } as Row
    row.base = base || row
    return row
  }
  ;[...line.routes].sort((a: any, b: any) => b.stops.length - a.stops.length || a.id.localeCompare(b.id)).forEach((r: any) => {
    let at: number[] | null = null, unfolded = false
    const visited = new Map<string, Row>(), shape = revisits(r.stops.map(stationOf), r.stops.map((stop: any) => stop.platformId))
    r.stops.forEach((stop: any, i: number) => {
      const id = stationOf(stop), again = shape[i]
      // Back at the station a Q branches off, on another platform: the rest of the route goes on down in rows of its own
      if (again && !again.retrace && !again.samePlatform) unfolded = true
      let row = newRow(id, stop.name)
      if (again && !again.retrace && again.samePlatform) {
        row = newRow(id, stop.name, visited.get(id)!.base)
      } else if (!unfolded && rowsOf(id).length) {
        // Rejoining another route's station
        const [k0, j0] = rowsOf(id)[0]
        row = newRow(id, stop.name, sections[k0][j0].base)
      }
      if (!at) {
        at = rowsOf(id)[0]
        if (!at) {
          sections.push([row])
          at = [sections.length - 1, 0]
        }
        visited.set(id, sections[at[0]][at[1]])
        return
      }
      const [k, j] = at, section = sections[k], from = section[j]
      if (from.id === id) return
      // Already side by side somewhere, preferably next to the row the route is on. Unfolded, only next to that row.
      const starts = unfolded ? [at] : rowsOf(from.id).sort((a, b) => Number(b[0] === k && b[1] === j) - Number(a[0] === k && a[1] === j))
      const side = starts.flatMap(([k2, j2]) => [[k2, j2 + 1], [k2, j2 - 1]]).find(([k2, j2]) => sections[k2][j2] && sections[k2][j2].id === id)
      if (side) {
        at = side
      } else if (j === section.length - 1) {
        section.push(row)
        at = [k, j + 1]
      } else if (j === 0) {
        section.unshift(row)
        at = [k, 0]
      } else {
        sections.splice(k + 1, 0, [newRow(from.id, from.name, from.base), row])
        at = [k + 1, 1]
      }
      if (!visited.has(id)) visited.set(id, sections[at[0]][at[1]])
    })
  })

  // A Q coming back to the platform it branched off at: the loop on top, and the stretch leading to it below
  for (let k = 0; k < sections.length; k++) {
    const section = sections[k], last = section[section.length - 1], j = section.indexOf(last.base)
    if (last.base === last || j <= 0) continue
    sections.splice(k, 1, section.slice(j), [newRow(last.id, last.name, last.base), ...section.slice(0, j).reverse()])
    k++
  }

  // A section with one end free and the other at the end of another carries that one on; one hanging off the middle of
  // another goes down from the station it hangs off; a section coming back to itself stays as it is
  const elsewhere = (k: number, id: string) => sections.some((section, k2) => k2 !== k && section.some(row => row.id === id))
  for (let merged = true; merged;) {
    merged = false
    for (let k = 0; k < sections.length && !merged; k++) {
      const section = sections[k], first = section[0], last = section[section.length - 1]
      if (section.length < 2 || elsewhere(k, first.id) === elsewhere(k, last.id)) continue
      const ordered = elsewhere(k, first.id) ? section : section.slice().reverse()
      const t = sections.findIndex((other, k2) => k2 !== k && other[0].id !== other[other.length - 1].id && (other[0].id === ordered[0].id || other[other.length - 1].id === ordered[0].id))
      if (t >= 0) {
        const other = sections[t], atEnd = other[other.length - 1].id === ordered[0].id
        // The station they share keeps the row that isn't a repeat
        const end = atEnd ? other[other.length - 1] : other[0], shared = end.base === end ? end : ordered[0]
        sections[t] = atEnd ? other.slice(0, -1).concat(shared, ordered.slice(1)) : ordered.slice(1).reverse().concat(shared, other.slice(1))
        sections.splice(k, 1)
        merged = true
      } else if (k > 0) {
        sections[k] = ordered
      }
    }
  }

  // A repeated station is greyed; one a Q comes back to on another platform, and the stations it goes back along, are rows
  const rows: any[] = [], made = sections.flat()
  sections.forEach((section, k) => section.forEach((row, j) => rows.push({ id: row.id, name: row.name, section: k, sectionStart: k > 0 && j === 0, repeat: row.base !== row, gap: 0 })))
  rows.forEach((row, i) => { row.group = made.indexOf(made[i].base) })
  const rowsById = new Map<string, number[]>()
  rows.forEach((row, i) => rowsById.set(row.id, (rowsById.get(row.id) || []).concat(i)))
  const adjacent = (a: number, b: number) => a === b || (Math.abs(a - b) === 1 && rows[a].section === rows[b].section)
  line.routes.forEach((r: any) => {
    // The rows with the fewest changes from one section to another, by the cost of leaving each stop from each row
    const options: number[][] = r.stops.map((stop: any) => rowsById.get(stationOf(stop)))
    let cost = new Map<number, number>(options.length ? options[0].map(d => [d, 0]) : [])
    const back: Map<number, number[]>[] = []
    for (let i = 1; i < options.length; i++) {
      const next = new Map<number, number>(), choice = new Map<number, number[]>()
      options[i].forEach(d => options[i].forEach(a => {
        let best = Infinity, before = -1
        cost.forEach((c, p) => {
          const total = c + (adjacent(p, a) ? 0 : 100)
          if (total < best) { best = total; before = p }
        })
        // Over to the station's row in another section, or to a row of its own, which is a way the route doesn't go
        const total = best + (a === d ? 0 : rows[a].group === rows[d].group ? 1 : 150)
        if (!next.has(d) || total < next.get(d)!) {
          next.set(d, total)
          choice.set(d, [a, before])
        }
      }))
      back.push(choice)
      cost = next
    }
    r.rows = []
    r.leaveRows = []
    if (!options.length) return
    let d = [...cost.entries()].reduce((best, entry) => (entry[1] < best[1] ? entry : best))[0]
    for (let i = options.length - 1; i > 0; i--) {
      const [a, before] = back[i - 1].get(d)!
      r.leaveRows[i] = d
      r.rows[i] = a
      d = before
    }
    r.rows[0] = r.leaveRows[0] = d
  })

  // The space above each row follows the fastest running time to it, half and half with even spacing; a section starts
  // a little further down
  const gaps: number[][] = rows.map(() => [])
  line.routes.forEach((r: any) => {
    const plans = r.tables.length ? r.tables[0].plans : []
    for (let i = 1; i < r.rows.length; i++) {
      const a = r.leaveRows[i - 1], b = r.rows[i]
      if (a !== b && adjacent(a, b) && plans[i] && r.hasTimes) gaps[Math.max(a, b)].push(Math.max(0, plans[i][0] - plans[i - 1][1]))
    }
  })
  const all = gaps.flat()
  const typical = all.length ? all.reduce((a, b) => a + b, 0) / all.length : 1
  rows.forEach((row, i) => { row.gap = i === 0 ? 0 : row.sectionStart ? SECTION_GAP : 0.5 + 0.5 * (gaps[i].length ? Math.min(...gaps[i]) / (typical || 1) : 1) })
  line.rows = rows
}

/** Each stop as the game sends its times: [arrival, departure, overtake, passes, tooFast], millis from the first stop */
export const toPlans = (times: number[][]): Plan[] => times.map(t => ({ arrival: t[0], departure: t[1], overtake: t[2] === 1, passes: t[3] === 1, tooFast: t[4] === 1 }))

/** The game's own times of a route's tables, as a times source (kept on the route) */
export const gameSource = (r: any): TimesSource => r.gameSource || (r.gameSource = r.tables.map((table: any) => ({ times: table.times, exceptions: new Map((table.exceptions || []).map((e: any) => [e.service, e.times])) })))

/** The planned times at each stop of a table's services, or of one service with its own exceptions; none when not known yet */
export function plansFor(r: any, source: TimesSource, tableIndex: number, service: number | null): Plan[] {
  const table = source[tableIndex]
  const times = table ? (service !== null && table.exceptions.get(service)) || table.times : null
  if (times && times.length === r.stops.length) return toPlans(times)
  return r.stops.map(() => ({ arrival: 0, departure: 0, overtake: false, passes: false, tooFast: false }))
}

/**
 * A route's services on a weekday: for each service, the first of its tables that runs that day, with its link, return,
 * suspension, orders to and from depots, its time in the operating day, and its planned times (its own where it has
 * exceptions).
 */
export function routeServices(r: any, timetable: any, source: TimesSource, weekday: number, dayStartMillis: number) {
  const services = new Map<number, any>()
  timetable.tables.forEach((table: any, tableIndex: number) => {
    if (((table.weekdays >> weekday) & 1) === 0) return
    const tablePlans = plansFor(r, source, tableIndex, null)
    const exceptions = new Map(table.exceptions.map((exception: any) => [exception.service, new Map(exception.stops.map((stop: any) => [stop.stop, stop]))]))
    table.services.forEach((service: number) => {
      if (services.has(service)) return
      const stopExceptions = exceptions.get(service) || null
      services.set(service, {
        id: r.id + "@" + service, route: r, service, table, tableIndex,
        link: table.links.find((l: any) => l.service === service),
        returns: table.returns.includes(service),
        suspended: (table.suspended || []).includes(service),
        deployOrder: (table.orders || []).find((order: any) => order.service === service && order.kind === "deploy") || null,
        returnOrder: (table.orders || []).find((order: any) => order.service === service && order.kind === "return") || null,
        // The time in the operating day, so the last trains after midnight come after the first ones
        start: mod(service - dayStartMillis, DAY),
        plans: stopExceptions ? plansFor(r, source, tableIndex, service) : tablePlans,
        tablePlans, exceptions: stopExceptions,
      })
    })
  })
  return [...services.values()].sort((a, b) => a.start - b.start)
}

/** A service by its id, route@service */
export function findService(routeById: Map<string, any>, id: string) {
  const [routeId, service] = id.split("@")
  const r = routeById.get(routeId)
  return r && r.services ? r.services.find((s: any) => s.service === Number(service)) || null : null
}

/** Arrival and departure at each stop, in milliseconds into the operating day */
export const timesOf = (s: { start: number; plans: Plan[] }) => s.plans.map(plan => [s.start + plan.arrival, s.start + plan.departure])

/** The line group shown */
export const currentLine = (data: any, lineKey: string | null) => (data ? data.lines.find((l: any) => l.key === lineKey) || null : null)
