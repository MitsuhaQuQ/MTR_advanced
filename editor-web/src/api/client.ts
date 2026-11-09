import type { EditorData } from "./types"

/**
 * The game opens the editor as …#key=<hex>. The old page's shell.js keeps the key in sessionStorage under the same name,
 * so this page finds it there when opened from the old one, and the other way round.
 */
const KEY_STORAGE = "mtr-editor-key"
export const key = (() => {
  const match = location.hash.match(/key=([0-9a-f]+)/)
  try {
    if (match) sessionStorage.setItem(KEY_STORAGE, match[1])
    return match ? match[1] : sessionStorage.getItem(KEY_STORAGE) || ""
  } catch {
    return match ? match[1] : ""
  }
})()
// The key leaves the address at once, so it isn't kept in the history or shown
if (/key=/.test(location.hash)) history.replaceState(null, "", location.pathname)

/** An endpoint answered with an error; status 401 is a wrong key, 503 no world loaded. */
export class ApiError extends Error {
  readonly status: number
  constructor(status: number) { super(String(status)); this.status = status }
}

// Endpoints live at the server's root, the page in /next/
const url = (path: string) => new URL("/api/" + path, location.origin).toString()

async function getJson<T>(path: string): Promise<T> {
  let response: Response
  try {
    response = await fetch(url(path), { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" })
  } catch {
    throw new ApiError(0)
  }
  if (!response.ok) throw new ApiError(response.status)
  return response.json() as Promise<T>
}

async function sendJson<T>(path: string, method: "POST" | "PUT", body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(url(path), { method, headers: { "X-MTR-Editor-Key": key, "Content-Type": "application/json" }, body: JSON.stringify(body), cache: "no-store" })
  } catch {
    throw new ApiError(0)
  }
  if (!response.ok) throw new ApiError(response.status)
  return response.json() as Promise<T>
}

/** Any endpoint by its name under /api/, posted. */
export const post = <T,>(path: string, body: unknown) => sendJson<T>(path, "POST", body)

export const api = {
  data: () => getJson<EditorData>("data"),
  revision: () => getJson<{ revision: number }>("revision"),
  draft: () => getJson<unknown>("draft"),
  saveDraft: (draft: unknown) => sendJson<unknown>("draft", "PUT", draft),
  /** Applies operations (EditorActions): { ok, errors: [{ index, message }], created: [{ id }] } */
  apply: (operations: unknown[]) => sendJson<{ ok: boolean; errors?: { index: number; message: string }[]; created?: { id: string }[] }>("apply", "POST", { operations }),
}

export type Connection = "connecting" | "ok" | "key" | "world" | "offline"
export const connectionOf = (error: unknown): Connection =>
  error instanceof ApiError ? (error.status === 401 ? "key" : error.status === 503 ? "world" : "offline") : "offline"
