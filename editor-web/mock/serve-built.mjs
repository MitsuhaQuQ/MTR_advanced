// Serves the editor folder as the game's EditorServer does (same Content-Security-Policy, key checked on /api/), with
// the example data, to check the built page and the old page together before a mod build:
//   node mock/serve-built.mjs [port]   then open http://localhost:<port>/#key=abc
import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { createServer } from "vite"

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, "../../common/src/main/resources/assets/mtr/editor")
const port = +(process.argv[2] || 8932)
const KEY = "abc"
// EditorServer.CONTENT_SECURITY_POLICY
const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml" }

// The fixture is TypeScript: load it through Vite's module runner
const vite = await createServer({ configFile: false, root: path.resolve(here, ".."), server: { middlewareMode: true }, logLevel: "error" })
const { mockData } = await vite.ssrLoadModule("/mock/fixture.ts")
let draft = { routes: {} }

http.createServer((req, res) => {
  res.setHeader("Content-Security-Policy", CSP)
  res.setHeader("X-Content-Type-Options", "nosniff")
  const url = decodeURIComponent((req.url || "/").split("?")[0])
  const json = body => { res.setHeader("Content-Type", "application/json"); res.end(JSON.stringify(body)) }
  if (url.startsWith("/api/")) {
    if (req.headers["x-mtr-editor-key"] !== KEY) { res.statusCode = 401; return res.end() }
    if (url === "/api/data") return json(mockData())
    if (url === "/api/revision") return json({ revision: 1 })
    if (url === "/api/draft") {
      if (req.method === "GET") return json(draft)
      let body = ""
      req.on("data", c => body += c)
      req.on("end", () => { draft = JSON.parse(body || "{}"); json({ ok: true }) })
      return
    }
    res.statusCode = 503
    return res.end()
  }
  let file = path.join(root, url)
  if (!file.startsWith(root)) { res.statusCode = 403; return res.end() }
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, "index.html")
  if (!fs.existsSync(file)) { res.statusCode = 404; return res.end() }
  res.setHeader("Content-Type", TYPES[path.extname(file)] || "application/octet-stream")
  res.setHeader("Cache-Control", "no-cache")
  fs.createReadStream(file).pipe(res)
}).listen(port, "127.0.0.1", () => console.log(`editor at http://localhost:${port}/#key=${KEY}  (new page: /next/)`))
