import { defineConfig, type Plugin } from "vite"
import react from "@vitejs/plugin-react"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { createMockApi } from "./mock/api.ts"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../common/src/main/resources/assets/mtr/editor")
/** The page's plain scripts, kept in the game's folder beside the built page and not bundled */
const SCRIPTS = ["i18n.js", "batch.js", "terminal.js"]

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
        // The page's plain scripts
        const file = (req.url || "").split("?")[0].slice(1)
        if (SCRIPTS.includes(file)) {
          res.setHeader("Content-Type", "text/javascript")
          res.end(fs.readFileSync(path.join(EDITOR, file)))
          return
        }
        if (!(await handle(req, res))) next()
      })
    },
  }
}

/**
 * The build goes into the game's editor folder itself, beside the plain scripts, which must stay: instead of emptying the
 * folder, only what a build puts there is removed first (the page, its hashed assets, the files of public/, and next/,
 * where the page was built until the old page went).
 */
function clearLastBuild(): Plugin {
  return {
    name: "mtr-clear-last-build",
    apply: "build",
    buildStart() {
      const built = ["index.html", "assets", "next", ...fs.readdirSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), "public"))]
      for (const name of built) {
        if (SCRIPTS.includes(name)) throw new Error(`public/${name} would replace the page's script of that name`)
        fs.rmSync(path.join(EDITOR, name), { recursive: true, force: true })
      }
    },
  }
}

export default defineConfig({
  plugins: [react(), mockEditorServer(), clearLastBuild()],
  // Served by the game at the root of its editor server; relative paths so the folder can move
  base: "./",
  build: {
    outDir: EDITOR,
    emptyOutDir: false,
    // The editor's Content-Security-Policy allows only scripts from its own files, never inline ones
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    chunkSizeWarningLimit: 800,
  },
})
