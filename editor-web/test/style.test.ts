/**
 * Drawing and styling go by the design system's tokens (CSS variables), in both themes. Colours and fonts written into
 * the old editor's code or styles were what broke dark mode and the colour rules (red only for problems), so they are
 * checked for here: any new one fails the build.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { describe, expect, it } from "vitest"

const HERE = path.dirname(fileURLToPath(import.meta.url))
/** What is left of the old editor's code and its styles (the old page's editor.js and editor.css until step 9) */
const FILES: Record<string, string> = { "oldEditor.js": "../src/legacy/oldEditor.js", "legacy.css": "../public/legacy.css" }
const read = (file: string) => fs.readFileSync(path.resolve(HERE, FILES[file]), "utf8")
/** Starting values of colour pickers in forms: a choice offered, not a colour drawn */
const PICKER_DEFAULTS = new Set(["#2f9e55", "#2f6fd6"])

describe("design tokens", () => {
  it("draws with no colour written into oldEditor.js", () => {
    const found = [...read("oldEditor.js").matchAll(/["'`](#[0-9a-fA-F]{3,8})["'`]/g)].map(m => m[1]).filter(colour => !PICKER_DEFAULTS.has(colour))
    expect(found).toEqual([])
  })
  it("draws text in the design system's fonts", () => {
    expect(read("oldEditor.js").match(/\d+px sans-serif/g) || []).toEqual([])
  })
  it("styles with no colour written outside the token definitions of legacy.css", () => {
    // A colour value (not an ID selector like #add-service), in a declaration that isn't a token's definition
    const colour = /#[0-9a-fA-F]{3,8}(?=[;\s,)])/
    const declarations = read("legacy.css").split(/[{};]/).map(part => part.trim()).filter(part => colour.test(part))
    expect(declarations.filter(part => !/^--[\w-]+:/.test(part))).toEqual([])
  })
  it("defines every drawing token in both themes", () => {
    const css = read("legacy.css")
    for (const token of ["--duty", "--now", "--connecting", "--boundary", "--late", "--siding", "--stabling", "--control-line", "--caution"]) {
      expect(css.match(new RegExp(token + ":", "g"))?.length, token).toBe(3)
    }
  })
})
