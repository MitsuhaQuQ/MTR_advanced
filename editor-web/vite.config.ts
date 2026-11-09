import { defineConfig, type Plugin } from "vite"
import react from "@vitejs/plugin-react"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { createMockApi } from "./mock/api.ts"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../common/src/main/resources/assets/mtr/editor")

/**
 * `npm run dev` only: stands in for the game's EditorServer. Serves the shared scripts from the old editor's folder and
 * the fake endpoints of mock/api.ts, so the page can be worked on without starting Minecraft.
 */
function mockEditorServer(): Plugin {
  const handle = createMockApi()
  return {
    name: "mtr-mock-editor-server",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        // The old page's scripts both pages load
        const file = (req.url || "").split("?")[0].slice(1)
        if (["i18n.js", "batch.js", "terminal.js"].includes(file)) {
          res.setHeader("Content-Type", "text/javascript")
          res.end(fs.readFileSync(path.join(EDITOR, file)))
          return
        }
        if (!(await handle(req, res))) next()
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
