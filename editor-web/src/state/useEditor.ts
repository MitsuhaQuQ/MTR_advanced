import { useEffect, useRef, useState } from "react"
import { api, connectionOf, type Connection } from "../api/client"
import type { Draft, EditorData } from "../api/types"
import { setLanguage } from "../i18n"

// As the old page: checked often so changes made in the game show soon, less often while the page is hidden
const POLL_MILLIS = 1500, POLL_HIDDEN_MILLIS = 5000
// The draft is shared with the old page, which may be open in another tab
const DRAFT_MILLIS = 5000

export type EditorState = {
  data: EditorData | null
  draft: Draft | null
  connection: Connection
  loadedAt: number
  /** Bumped when the language changes, so text is translated again */
  language: string
}

/** Reads the game's data whenever its revision changes, and the shared draft now and then. */
export function useEditor(): EditorState {
  const [state, setState] = useState<EditorState>({ data: null, draft: null, connection: "connecting", loadedAt: 0, language: "" })
  const revision = useRef<number | null>(null)

  useEffect(() => {
    let stopped = false, timer = 0
    const poll = async () => {
      try {
        const { revision: next } = await api.revision()
        if (next !== revision.current) {
          const data = await api.data()
          revision.current = next
          setLanguage(data.language)
          setState(s => ({ ...s, data, connection: "ok", loadedAt: Date.now(), language: data.language }))
        } else {
          setState(s => (s.connection === "ok" ? s : { ...s, connection: "ok" }))
        }
      } catch (e) {
        const connection = connectionOf(e)
        setState(s => (s.connection === connection ? s : { ...s, connection }))
      }
      if (!stopped) timer = window.setTimeout(poll, document.hidden ? POLL_HIDDEN_MILLIS : POLL_MILLIS)
    }
    poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [])

  useEffect(() => {
    let stopped = false, timer = 0
    const read = async () => {
      try {
        const draft = await api.draft()
        setState(s => ({ ...s, draft: draft && draft.routes ? draft : { routes: {} } }))
      } catch {
        // The connection state comes from the revision poll
      }
      if (!stopped) timer = window.setTimeout(read, DRAFT_MILLIS)
    }
    read()
    return () => { stopped = true; clearTimeout(timer) }
  }, [])

  return state
}
