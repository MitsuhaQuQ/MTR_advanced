/**
 * The times of the draft's timetables, which the game works out (retiring the old code, step 4: the old timesSource and
 * sendTimes). The page doesn't work out times itself: a route whose timetable is drafted asks the game (api/times) a
 * moment after its last change, all such routes at once, and shows the game's own times until the answer comes; a draft
 * asked for and not answered is asked again with its next change.
 */
import { gameSource, type TimesSource } from "./services"

type Answer = { routes: { id: string; tables: { times: number[][]; exceptions: { service: number; times: number[][] }[] }[] }[] }

export class DraftTimes {
  readonly answered = new Map<string, { key: string; tables: TimesSource }>()
  readonly requested = new Map<string, string>()
  private timer: ReturnType<typeof setTimeout> | 0 = 0

  /**
   * @param send asks the game for the times of these routes' timetables
   * @param draftTimetable the route's drafted timetable, if any
   * @param routeById the route with this ID now
   * @param answeredNow called once times have come, to make the services again and draw
   */
  constructor(private readonly send: (routes: { id: string; timetable: unknown }[]) => Promise<Answer>, private readonly draftTimetable: (r: any) => unknown, private readonly routeById: (id: string) => any, private readonly answeredNow: () => void, private readonly delay = 150) {}

  /** Where a route's times are taken from: the game's for its own timetable, or the game's answer for the draft */
  source(r: any): TimesSource {
    const timetable = this.draftTimetable(r)
    if (!timetable) { this.requested.delete(r.id); return gameSource(r) }
    const key = JSON.stringify([timetable, r.hasTimes ? r.fastest : null])
    const cached = this.answered.get(r.id)
    if ((!cached || cached.key !== key) && this.requested.get(r.id) !== key) {
      this.requested.set(r.id, key)
      if (this.timer) clearTimeout(this.timer)
      this.timer = setTimeout(() => { void this.flush() }, this.delay)
    }
    // Until the game answers, the times it last gave are shown
    return cached ? cached.tables : gameSource(r)
  }

  /** Sends the drafts waiting for times, and takes the answer */
  async flush(): Promise<void> {
    const sent = [...this.requested.entries()].filter(([id, key]) => !this.answered.has(id) || this.answered.get(id)!.key !== key)
    const routes = sent.map(([id]) => this.routeById(id)).filter(r => r && this.draftTimetable(r))
    if (!routes.length) return
    const keys = new Map(sent)
    try {
      const result = await this.send(routes.map(r => ({ id: r.id, timetable: JSON.parse(JSON.stringify(this.draftTimetable(r))) })))
      result.routes.forEach(route => {
        const current = this.routeById(route.id)
        const draft = current && this.draftTimetable(current)
        const key = draft && JSON.stringify([draft, current.hasTimes ? current.fastest : null])
        if (!key || key !== keys.get(route.id) || this.requested.get(route.id) !== key) return
        this.answered.set(route.id, {
          key,
          tables: route.tables.map(table => ({ times: table.times, exceptions: new Map(table.exceptions.map(e => [e.service, e.times])) })),
        })
      })
    } catch {
      // Asked again with the next change
      sent.forEach(([id, key]) => { if (this.requested.get(id) === key) this.requested.delete(id) })
      return
    }
    this.answeredNow()
  }
}
