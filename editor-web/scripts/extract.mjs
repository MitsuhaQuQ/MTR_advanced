/**
 * Cuts the old editor's top-level declarations (one tab deep inside its IIFE) out of editor.js, as written. Used by the
 * parity tests and by gen-legacy.mjs. It knows enough JavaScript to find where a declaration ends: strings, template
 * literals with ${…}, regular expression literals and comments.
 */
const REGEX_BEFORE = new Set("(,=:[!&|?{};+-*%<>~^".split(""))

function skipString(src, i, quote) {
  i++
  while (src[i] !== quote) i += src[i] === "\\" ? 2 : 1
  return i + 1
}

/** Skips a template literal's text from after its opening backquote to after its closing one, scanning ${…} as code. */
function skipTemplate(src, i) {
  while (i < src.length) {
    const c = src[i]
    if (c === "\\") { i += 2; continue }
    if (c === "`") return i + 1
    if (c === "$" && src[i + 1] === "{") {
      i = skipCode(src, i + 2, "}")
      continue
    }
    i++
  }
  throw new Error("unclosed template")
}

/** Skips code until the bracket `close` at depth 0, returning the index after it. */
function skipCode(src, i, close) {
  let depth = 0, last = ""
  while (i < src.length) {
    const c = src[i], next = src[i + 1]
    if (c === "/" && next === "/") { i = src.indexOf("\n", i); continue }
    if (c === "/" && next === "*") { i = src.indexOf("*/", i + 2) + 2; continue }
    if (c === "'" || c === '"') { i = skipString(src, i, c); last = c; continue }
    if (c === "`") { i = skipTemplate(src, i + 1); last = "`"; continue }
    if (c === "/" && isRegexStart(src, i, last)) { i = skipRegex(src, i); last = "/"; continue }
    if (c === "(" || c === "[" || c === "{") depth++
    else if (c === ")" || c === "]" || c === "}") {
      if (depth === 0 && c === close) return i + 1
      depth--
    }
    if (!/\s/.test(c)) last = c
    i++
  }
  throw new Error("unbalanced code")
}

const isRegexStart = (src, i, last) => REGEX_BEFORE.has(last) || last === "" || /\b(return|typeof|case)$/.test(src.slice(Math.max(0, i - 8), i).trimEnd())

function skipRegex(src, i) {
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
  return i
}

/** The end (exclusive) of a declaration starting at `start`: a function's closing brace, or a const/let's semicolon. */
export function endOf(src, start, isFunction) {
  let i = start, depth = 0, last = "", sawBody = false
  while (i < src.length) {
    const c = src[i], next = src[i + 1]
    if (c === "/" && next === "/") { i = src.indexOf("\n", i); continue }
    if (c === "/" && next === "*") { i = src.indexOf("*/", i + 2) + 2; continue }
    if (c === "'" || c === '"') { i = skipString(src, i, c); last = c; continue }
    if (c === "`") { i = skipTemplate(src, i + 1); last = "`"; continue }
    if (c === "/" && isRegexStart(src, i, last)) { i = skipRegex(src, i); last = "/"; continue }
    if (c === "(" || c === "[" || c === "{") { depth++; if (c === "{" && depth === 1) sawBody = true }
    else if (c === ")" || c === "]" || c === "}") {
      depth--
      if (isFunction && c === "}" && depth === 0 && sawBody) return i + 1
    } else if (c === ";" && depth === 0 && !isFunction) return i + 1
    if (!/\s/.test(c)) last = c
    i++
  }
  throw new Error("no end found at " + start)
}

/** The names a `const`/`let` declaration declares: `const a = 1, b = 2;` gives a and b. */
export function declaredNames(text) {
  const body = text.replace(/^(const|let) /, "")
  const names = []
  let i = 0, depth = 0, expectName = true, last = ""
  while (i < body.length) {
    const c = body[i]
    if (expectName) {
      const match = /^\s*([A-Za-z_$][\w$]*)/.exec(body.slice(i))
      if (match) { names.push(match[1]); i += match[0].length; expectName = false; continue }
    }
    if (c === "'" || c === '"') { i = skipString(body, i, c); last = c; continue }
    if (c === "`") { i = skipTemplate(body, i + 1); last = "`"; continue }
    if (c === "/" && body[i + 1] === "/") { i = body.indexOf("\n", i); continue }
    if (c === "/" && body[i + 1] === "*") { i = body.indexOf("*/", i + 2) + 2; continue }
    if (c === "/" && isRegexStart(body, i, last)) { i = skipRegex(body, i); last = "/"; continue }
    if (c === "(" || c === "[" || c === "{") depth++
    else if (c === ")" || c === "]" || c === "}") depth--
    else if (c === "," && depth === 0) expectName = true
    if (!/\s/.test(c)) last = c
    i++
  }
  return names
}

/**
 * Every top-level declaration of editor.js, in order: { kind: "function" | "const" | "let", names, text, start, end }.
 * Statements that aren't declarations (event listeners, the start-up code) are left out.
 */
export function declarations(src) {
  const result = []
  const pattern = /^\t((?:async )?function ([A-Za-z_$][\w$]*)\(|(const|let) )/gm
  let match
  while ((match = pattern.exec(src))) {
    const start = match.index + 1
    const isFunction = Boolean(match[2])
    const end = endOf(src, start, isFunction)
    const text = src.slice(start, end)
    result.push({ kind: isFunction ? "function" : match[3], names: isFunction ? [match[2]] : declaredNames(text), text, start, end })
    pattern.lastIndex = end
  }
  return result
}

/** The source of one top-level function or constant, by name. */
export function extract(src, name) {
  const found = declarations(src).find(d => d.names.includes(name))
  if (!found) throw new Error(`${name} not found in editor.js`)
  return found.text
}
