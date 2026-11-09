/**
 * The old editor's own code, for parity tests: functions and constants are cut out of editor.js by name, as written,
 * and run in a sandbox with the state they read (data, draft …) given by the test. The new page's modules must give
 * the same results on the same input, so a behaviour moved over can't drift from the old page without a test failing.
 */
import fs from "node:fs"
import path from "node:path"
import vm from "node:vm"
import { fileURLToPath } from "node:url"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")
export const OLD_SOURCE = fs.readFileSync(path.join(EDITOR, "editor.js"), "utf8")
const I18N_SOURCE = fs.readFileSync(path.join(EDITOR, "i18n.js"), "utf8")

const REGEX_BEFORE = new Set("(,=:[!&|?{};+-*%<>~^".split(""))

/** The end (exclusive) of a function declaration or a `const x = …;` starting at `start`. */
function endOf(src: string, start: number, isFunction: boolean): number {
  const stack: string[] = []
  let i = start, lastSignificant = "", sawBody = false
  while (i < src.length) {
    const c = src[i], next = src[i + 1]
    if (c === "/" && next === "/") { i = src.indexOf("\n", i); if (i < 0) return src.length; continue }
    if (c === "/" && next === "*") { i = src.indexOf("*/", i + 2) + 2; continue }
    if (c === "'" || c === '"') {
      i++
      while (src[i] !== c) i += src[i] === "\\" ? 2 : 1
      i++; lastSignificant = c; continue
    }
    if (c === "`") { stack.push("`"); i++; i = scanTemplate(src, i, stack); lastSignificant = "`"; continue }
    if (c === "/" && (REGEX_BEFORE.has(lastSignificant) || /\b(return|typeof)$/.test(src.slice(Math.max(0, i - 8), i).trimEnd()))) {
      i++
      let inClass = false
      while (src[i] !== "/" || inClass) {
        if (src[i] === "\\") i++
        else if (src[i] === "[") inClass = true
        else if (src[i] === "]") inClass = false
        i++
      }
      i++
      while (/[a-z]/.test(src[i])) i++
      lastSignificant = "/"; continue
    }
    if (c === "(" || c === "[" || c === "{") { stack.push(c); if (c === "{" && stack.length === 1) sawBody = true }
    else if (c === ")" || c === "]" || c === "}") {
      stack.pop()
      if (isFunction && c === "}" && stack.length === 0 && sawBody) return i + 1
    } else if (c === ";" && stack.length === 0 && !isFunction) return i + 1
    if (!/\s/.test(c)) lastSignificant = c
    i++
  }
  throw new Error("no end found")
}

/** Skips a template literal's text up to its closing backquote, scanning ${…} parts as code. */
function scanTemplate(src: string, i: number, stack: string[]): number {
  while (i < src.length) {
    const c = src[i]
    if (c === "\\") { i += 2; continue }
    if (c === "`") { stack.pop(); return i + 1 }
    if (c === "$" && src[i + 1] === "{") {
      // Code inside ${…} until its matching brace
      let depth = 1
      i += 2
      while (depth > 0) {
        const d = src[i]
        if (d === "`") { stack.push("`"); i = scanTemplate(src, i + 1, stack); continue }
        if (d === "'" || d === '"') { i++; while (src[i] !== d) i += src[i] === "\\" ? 2 : 1; i++; continue }
        if (d === "{") depth++
        else if (d === "}") depth--
        i++
      }
      continue
    }
    i++
  }
  throw new Error("unclosed template")
}

/** The source of one top-level (one tab deep) function or constant of editor.js. */
export function extract(name: string): string {
  const pattern = new RegExp(`^\\t((?:async )?function ${name}\\(|const ${name} = )`, "m")
  const match = pattern.exec(OLD_SOURCE)
  if (!match) throw new Error(`${name} not found in editor.js`)
  const start = match.index + 1
  return OLD_SOURCE.slice(start, endOf(OLD_SOURCE, start, match[1].includes("function")))
}

/**
 * Runs the named old functions in a sandbox holding `context` (the old page's variables they read), with the old
 * page's i18n.js loaded so tr() works, and returns them.
 */
export function loadOld<T extends Record<string, (...args: any[]) => any>>(names: string[], context: Record<string, unknown> = {}): T & { sandbox: Record<string, any> } {
  const sandbox: Record<string, any> = {
    console, BigInt, Map, Set, JSON, Math, Object, Array, Number, String, Boolean,
    navigator: { language: "zh-CN" }, localStorage: { getItem: () => null, setItem: () => {} },
    document: { addEventListener: () => {}, documentElement: {}, querySelectorAll: () => [] },
    ...context,
  }
  vm.createContext(sandbox)
  vm.runInContext(I18N_SOURCE.replace(/^const /gm, "var "), sandbox)
  const code = names.map(extract).join("\n").replace(/^(const|let) /gm, "var ")
  vm.runInContext(code + `\n;globalThis.__old = { ${names.join(", ")} };`, sandbox)
  return Object.assign({ sandbox }, sandbox.__old) as T & { sandbox: Record<string, any> }
}
