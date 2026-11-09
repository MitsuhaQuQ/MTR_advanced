import { defineConfig, type Plugin } from "vite"
import react from "@vitejs/plugin-react"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { mockData } from "./mock/fixture"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../common/src/main/resources/assets/mtr/editor")

/**
 * `npm run dev` only: stands in for the game's EditorServer. Serves the shared i18n.js from the old editor's folder and
 * fake api/data, api/revision and api/draft, so the page can be worked on without starting Minecraft.
 */
function mockEditorServer(): Plugin {
  let draft: unknown = { routes: {} }
  return {
    name: "mtr-mock-editor-server",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = (req.url || "").split("?")[0]
        const json = (body: unknown) => { res.setHeader("Content-Type", "application/json"); res.end(JSON.stringify(body)) }
        if (url === "/i18n.js") { res.setHeader("Content-Type", "text/javascript"); res.end(fs.readFileSync(path.join(EDITOR, "i18n.js"))); return }
        if (url === "/api/data") return json(mockData())
        if (url === "/api/revision") return json({ revision: 1 })
        if (url === "/api/draft") {
          if (req.method === "GET") return json(draft)
          let body = ""
          req.on("data", chunk => body += chunk)
          req.on("end", () => { draft = JSON.parse(body || "{}"); json({ ok: true }) })
          return
        }
        if (url.startsWith("/api/")) { res.statusCode = 503; res.end(); return }
        next()
      })
    },
  }
}

export default defineConfig({
  plugins: [react(), mockEditorServer()],
  // Served by the game at /next/; relative paths so the folder can move
  base: "./",
  build: {
    outDir: path.join(EDITOR, "next"),
    emptyOutDir: true,
    // The editor's Content-Security-Policy allows only scripts from its own files, never inline ones
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    chunkSizeWarningLimit: 800,
  },
})
