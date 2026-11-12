/**
 * Moving work between depots (0.69.2, Q world 2026-11-12): another depot's duty group put into a depot on a vehicle of
 * its own, whatever vehicle it ran on there, and a line's routes moved from one depot to another with their duties, the
 * old depot's duties of the line dropped. A line runs a different vehicle by moving to a depot that has it; two depots
 * swap lines by two moves. All of it goes into the draft only: the depots' routes and sidings as the depot panel stores
 * them, the duties as a duty edit stores them.
 *
 * The functions here change the objects they are given and read nothing else, so the panels and the tests call them alike.
 */
import { newDutyId, dutyIdOf, startOf } from "./duties"

type Slot = any[]
export type Duties = { mode: string; sidings: Record<string, Slot[]>; auto?: boolean }

/** A depot's sidings of a vehicle, in the depot's order ("" for none set) */
export const sidingsOf = (depot: any, vehicle: string) => (depot.sidings || []).filter((siding: any) => (siding.vehicle || "") === vehicle)

/** The vehicles a depot has sidings of, each once, in the order of its sidings */
export const vehiclesOf = (depot: any): string[] => [...new Set<string>((depot.sidings || []).map((siding: any) => siding.vehicle || ""))]

/** A depot's duties run by duty map, by "vehicle|days": how many duties on each set of weekdays for each vehicle */
export function dutyGroupsOf(depot: any, duties: Duties) {
  const groups = new Map<string, number>()
  if (duties.mode !== "diagram") return groups
  const vehicleOf = new Map<string, string>((depot.sidings || []).map((siding: any) => [String(siding.id), siding.vehicle || ""]))
  Object.entries(duties.sidings).forEach(([sidingId, slots]) => slots.forEach(slot => slot.forEach((day: any) => {
    const key = (vehicleOf.get(sidingId) || "") + "|" + day.days
    groups.set(key, (groups.get(key) || 0) + 1)
  })))
  return groups
}

/**
 * Puts days of duties into sidings, each into the first slot of its siding free on its days; a slot's own duty number is
 * kept, a new slot gets a new one. The sidings given in order take the duties of the source sidings in order, the last
 * taking the rest.
 *
 * @return how many days of duties were put
 */
function putDays(duties: Duties, targets: any[], perSource: any[][]) {
  let count = 0
  perSource.forEach((days, k) => {
    const target = targets[Math.min(k, targets.length - 1)]
    const slots = duties.sidings[target.id] || (duties.sidings[target.id] = [])
    days.forEach(day => {
      let index = slots.findIndex(each => !each.some((other: any) => other.days & day.days))
      if (index < 0) {
        index = slots.length
        slots.push([])
      }
      const id = dutyIdOf(slots[index]) || newDutyId()
      slots[index].push({ days: day.days, stay: day.stay, next: -1, duty: id, start: slots[index].length ? startOf(slots[index]) : "prefer", nextDuty: "0", returnSiding: "0", fromOvernight: false, segments: JSON.parse(JSON.stringify(day.segments)) })
      count++
    })
  })
  return count
}

/**
 * Another depot's duties of one vehicle on some weekdays put into a depot's duties on a vehicle of the depot (the same or
 * another): the depot's duties of that vehicle on those days go, and the depot runs by its duties.
 *
 * @return how many duties were put, or -1 when the depot has no siding of the vehicle
 */
export function applyDutyGroup(duties: Duties, depot: any, source: any, sourceDuties: Duties, vehicle: string, mask: number, targetVehicle: string = vehicle) {
  const targets = sidingsOf(depot, targetVehicle)
  if (!targets.length) return -1
  duties.mode = "diagram"
  targets.forEach((target: any) => (duties.sidings[target.id] || []).forEach(slot => {
    for (let i = slot.length - 1; i >= 0; i--) {
      slot[i].days &= ~mask
      if (!slot[i].days) slot.splice(i, 1)
    }
  }))
  return putDays(duties, targets, sidingsOf(source, vehicle).map((siding: any) => (sourceDuties.sidings[siding.id] || []).flatMap(slot => slot.filter((day: any) => day.days === mask))))
}

/** Takes away the slots left with no days, the slots after them renumbered in each next-slot link of the same siding */
function dropEmptySlots(duties: Duties) {
  Object.values(duties.sidings).forEach(slots => {
    for (let index = slots.length - 1; index >= 0; index--) {
      if (slots[index].length) continue
      slots.splice(index, 1)
      slots.forEach(slot => slot.forEach((day: any) => {
        if (day.next === undefined || day.next < 0) return
        if (day.next === index) day.next = -1
        else if (day.next > index) day.next--
      }))
    }
  })
}

/** Whether a depot's duties run any service of the routes */
export const hasDutiesOf = (duties: Duties, routeIds: string[]) =>
  Object.values(duties.sidings).some(slots => slots.some(slot => slot.some((day: any) => day.segments.some((segment: any) => routeIds.includes(segment.route)))))

/**
 * The services of some routes taken out of a depot's duties (the source's side of a move): a day of a duty keeps its other
 * services, a day left with none goes, and so does a slot left with no days.
 *
 * @return the days taken, by the index of their siding among the source's sidings of each vehicle: vehicle → days of
 * each siding of it, each day with only the routes' services
 */
export function takeRoutes(duties: Duties, depot: any, routeIds: string[]) {
  const taken = new Map<string, any[][]>()
  vehiclesOf(depot).forEach(vehicle => {
    const perSiding = sidingsOf(depot, vehicle).map((siding: any) => {
      const days: any[] = []
      ;(duties.sidings[siding.id] || []).forEach(slot => {
        const fromSlot: any[] = []
        for (let i = slot.length - 1; i >= 0; i--) {
          const day = slot[i]
          const moved = day.segments.filter((segment: any) => routeIds.includes(segment.route))
          if (!moved.length) continue
          fromSlot.unshift(Object.assign({}, day, { segments: moved, stay: moved.length === day.segments.length ? day.stay : false }))
          day.segments = day.segments.filter((segment: any) => !routeIds.includes(segment.route))
          if (!day.segments.length) slot.splice(i, 1)
        }
        days.push(...fromSlot)
      })
      return days
    })
    if (perSiding.some((days: any[]) => days.length)) taken.set(vehicle, perSiding)
  })
  dropEmptySlots(duties)
  return taken
}

/**
 * The days a move takes put into the target depot's duties on one vehicle, the depot's other duties kept; the depot then
 * runs by its duties.
 *
 * @return how many days of duties were put, or -1 when the depot has no siding of the vehicle
 */
export function putTaken(duties: Duties, depot: any, taken: Map<string, any[][]>, targetVehicle: string) {
  const targets = sidingsOf(depot, targetVehicle)
  if (!targets.length) return -1
  duties.mode = "diagram"
  let count = 0
  taken.forEach(perSiding => { count += putDays(duties, targets, perSiding) })
  return count
}

/** A depot's entry as the depot panel stores it (depotEntryOf): its routes, and each siding's routes (none for all) */
export type DepotEntry = { routes: string[]; sidings: Record<string, { fleet: number; routes: string[] }>; [key: string]: any }

/**
 * Routes taken out of a depot's entry: from its routes and from each siding's. A siding that ran only those routes then
 * runs all the depot's routes left, as the depot panel stores a siding with none ticked.
 */
export function entryWithout(entry: DepotEntry, routeIds: string[]) {
  entry.routes = entry.routes.filter(id => !routeIds.includes(id))
  Object.values(entry.sidings).forEach(siding => { siding.routes = siding.routes.filter(id => !routeIds.includes(id)) })
  return entry
}

/**
 * Routes added to a depot's entry: to its routes, and to each siding of the vehicle that runs only some of the depot's
 * routes (one running all of them runs these too).
 */
export function entryWith(entry: DepotEntry, depot: any, routeIds: string[], vehicle: string) {
  routeIds.forEach(id => { if (!entry.routes.includes(id)) entry.routes.push(id) })
  sidingsOf(depot, vehicle).forEach((siding: any) => {
    const own = entry.sidings[siding.id]
    if (own && own.routes.length) routeIds.forEach(id => { if (!own.routes.includes(id)) own.routes.push(id) })
  })
  return entry
}
