/**
 * The fake editor endpoints both mock servers use (vite dev, serve-built.mjs). Applying takes everything except
 * changes to route 202's timetable, which it answers as changed in the game meanwhile (CONFLICT), so the page's conflict
 * handling can be tried. GET /api/mock/applied returns the last operations sent, for checks.
 */
import type { IncomingMessage, ServerResponse } from "node:http"
import { mockData } from "./fixture.ts"

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
