/**
 * The fake editor endpoints both mock servers use (vite dev, serve-built.mjs). Applying takes everything except
 * changes to route 202's timetable, which it answers as changed in the game meanwhile (CONFLICT), so the page's conflict
 * handling can be tried. GET /api/mock/applied returns the last operations sent, for checks.
 */
import type { IncomingMessage, ServerResponse } from "node:http"
import { mockData } from "./fixture.ts"
import { exampleTrack } from "./track.ts"

export function createMockApi() {
  let draft: unknown = { routes: {} }
  let revision = 1
  let applied: unknown[] = []
  const data = mockData()

  const readBody = (req: IncomingMessage) => new Promise<string>(resolve => {
    let body = ""
    req.on("data", chunk => { body += chunk })
    req.on("end", () => resolve(body))
  })

  /** @return whether the request was an API call it answered */
  return async function handle(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const url = (req.url || "").split("?")[0]
    if (!url.startsWith("/api/")) return false
    const json = (body: unknown) => { res.setHeader("Content-Type", "application/json"); res.end(JSON.stringify(body)) }
    switch (url) {
      case "/api/data": json(data); return true
      case "/api/revision": json({ revision }); return true
      case "/api/mock/applied": json(applied); return true
      case "/api/draft":
        if (req.method === "GET") json(draft)
        else { draft = JSON.parse((await readBody(req)) || "{}"); json({ ok: true }) }
        return true
      case "/api/route-layout": await readBody(req); json(exampleTrack().layout); return true
      case "/api/network": await readBody(req); json(exampleTrack().network); return true
      case "/api/console": json(exampleTrack().console); return true
      case "/api/audio": {
        // The media library's list (EditorAudio): two recordings, one not on this computer yet
        if (req.method === "POST") { await readBody(req); json({ ok: true }); return true }
        const entry = (key: string, name: string, cached: boolean, mine: boolean) => ({
          key, name, reference: key, sound: "mtr_audio:" + key.replace(":", "/"), soundId: name.replace(".ogg", ""), hash: key.length.toString(16).padStart(64, "0"),
          uploaderName: key.split(":")[0], size: 182400, durationMillis: 6200, uploadedAt: Date.now() - 3600000, cached, downloading: false, mine, canDelete: mine,
        })
        json({
          language: "zh_cn", canUpload: true, maxFileBytes: 2 * 1024 * 1024, maxTotalBytes: 50 * 1024 * 1024, totalBytes: 364800, maxSeconds: 60, requests: [],
          entries: [entry("mitsuha:next_central.ogg", "next_central.ogg", true, true), entry("rail_fan:transfer_l2.ogg", "transfer_l2.ogg", false, false)],
        })
        return true
      }
      case "/api/times": {
        // The draft's times as the game would work them out: here the routes' own run times, for each table
        const { routes } = JSON.parse((await readBody(req)) || "{}")
        json({ routes: routes.map((r: any) => {
          const route = data.routes.find((each: any) => each.id === r.id)
          return { id: r.id, tables: r.timetable.tables.map(() => ({ times: route ? route.tables[0].times : [], exceptions: [] })) }
        }) })
        return true
      }
      case "/api/apply": {
        const { operations } = JSON.parse((await readBody(req)) || "{}")
        applied = operations
        const errors = operations.map((op: any, index: number) => (op.type === "timetable" && op.route === "202" && !op.force ? { index, message: "CONFLICT" } : null)).filter(Boolean)
        // What was taken changes the game's data: here only the revision, so the page reads it again
        if (errors.length < operations.length) revision++
        json({ ok: errors.length === 0, errors, created: [] })
        return true
      }
      default:
        res.statusCode = 503
        res.end()
        return true
    }
  }
}
