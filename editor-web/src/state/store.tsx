/**
 * The page's state: the game's data (polled as the old page does) and the draft, with undo, saving and applying.
 *
 * The draft is shared with the old page through api/draft. Changes here are saved 400 ms after the last one, like the old
 * page; the saved draft is read every 5 s and taken over when it changed elsewhere (the old page in another tab) and
 * nothing here is waiting to be saved. Taking it over clears undo, which would otherwise undo the other page's work.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { api, connectionOf, type Connection } from "../api/client"
import type { EditorData } from "../api/types"
import { setLanguage, tr } from "../i18n"
import { buildModel, type Model } from "../core/model"
import { type ApplyResult, type Draft, draftOperations, emptyDraft, normalizeDraft, settleApply } from "../core/draft"
import { onLegacyDraft, pushData, pushDraft } from "../legacy/engine"

const POLL_MILLIS = 1500, POLL_HIDDEN_MILLIS = 5000, DRAFT_MILLIS = 5000, SAVE_DELAY = 400, UNDO_DEPTH = 100

type History = { past: Draft[]; now: Draft; future: Draft[] }

export type Store = {
  model: Model | null
  connection: Connection
  loadedAt: number
  language: string
  draft: Draft
  draftLoaded: boolean
  /** Changes the draft, as one step of undo */
  edit: (change: (draft: Draft) => Draft) => void
  undo: () => void
  redo: () => void
  canUndo: boolean
  canRedo: boolean
  applying: boolean
  applyResult: ApplyResult | null
  boundaryErrors: Record<string, string>
  apply: () => Promise<void>
  dismissResult: () => void
  /** Sends operations at once, not through the draft (taking a train back, a dispatcher's command) */
  sendNow: (operations: any[]) => Promise<boolean>
  /** Reads the game's data again now */
  refresh: () => void
}

const StoreContext = createContext<Store | null>(null)
export const useStore = () => useContext(StoreContext)!

export function StoreProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<EditorData | null>(null)
  const [connection, setConnection] = useState<Connection>("connecting")
  const [loadedAt, setLoadedAt] = useState(0)
  const [language, setLanguageState] = useState("")
  const [history, setHistory] = useState<History>({ past: [], now: emptyDraft(), future: [] })
  const [draftLoaded, setDraftLoaded] = useState(false)
  const [applying, setApplying] = useState(false)
  const [applyResult, setApplyResult] = useState<ApplyResult | null>(null)
  const [boundaryErrors, setBoundaryErrors] = useState<Record<string, string>>({})
  const revision = useRef<number | null>(null)
  const pollNow = useRef<() => void>(() => {})
  // The draft as last saved or read, to tell a change made elsewhere from our own
  const savedJson = useRef("")
  const saveTimer = useRef(0)
  const dirty = useRef(false)

  // ---------- The game's data ----------
  useEffect(() => {
    let stopped = false, timer = 0
    const poll = async () => {
      clearTimeout(timer)
      try {
        const { revision: next } = await api.revision()
        if (next !== revision.current) {
          const fresh = await api.data()
          revision.current = next
          setLanguage(fresh.language)
          setData(fresh)
          // The old code draws the views it still owns from its own copy
          pushData(fresh)
          setLanguageState(fresh.language)
          setLoadedAt(Date.now())
        }
        setConnection("ok")
      } catch (e) {
        setConnection(connectionOf(e))
      }
      if (!stopped) timer = window.setTimeout(poll, document.hidden ? POLL_HIDDEN_MILLIS : POLL_MILLIS)
    }
    pollNow.current = () => { revision.current = null; poll() }
    poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [])

  // ---------- The draft ----------
  const save = useCallback((draft: Draft) => {
    dirty.current = true
    clearTimeout(saveTimer.current)
    saveTimer.current = window.setTimeout(async () => {
      const json = JSON.stringify(draft)
      try {
        await api.saveDraft(draft)
        savedJson.current = json
      } catch {
        // Kept here; saved with the next change
      }
      dirty.current = false
    }, SAVE_DELAY)
  }, [])

  useEffect(() => {
    let stopped = false, timer = 0
    const read = async () => {
      if (!dirty.current) {
        try {
          const saved = await api.draft()
          const json = JSON.stringify(saved)
          if (json !== savedJson.current && !dirty.current) {
            savedJson.current = json
            setHistory({ past: [], now: normalizeDraft(saved), future: [] })
          }
          setDraftLoaded(true)
        } catch {
          // The connection shows in the data poll
        }
      }
      if (!stopped) timer = window.setTimeout(read, DRAFT_MILLIS)
    }
    read()
    return () => { stopped = true; clearTimeout(timer) }
  }, [])

  const edit = useCallback((change: (draft: Draft) => Draft) => {
    setHistory(h => {
      const now = change(h.now)
      if (now === h.now) return h
      save(now)
      return { past: [...h.past.slice(-UNDO_DEPTH), h.now], now, future: [] }
    })
  }, [save])
  const undo = useCallback(() => setHistory(h => {
    if (!h.past.length) return h
    const now = h.past[h.past.length - 1]
    save(now)
    return { past: h.past.slice(0, -1), now, future: [h.now, ...h.future] }
  }), [save])
  const redo = useCallback(() => setHistory(h => {
    if (!h.future.length) return h
    const now = h.future[0]
    save(now)
    return { past: [...h.past, h.now], now, future: h.future.slice(1) }
  }), [save])

  const model = useMemo(() => (data ? buildModel(data) : null), [data])

  // The old code works from the same draft: given ours whenever it changes, and its changes come back as steps of undo
  useEffect(() => { pushDraft(history.now) }, [history.now])
  useEffect(() => { onLegacyDraft(draft => edit(() => normalizeDraft(draft))) }, [edit])

  // ---------- Applying ----------
  const apply = useCallback(async () => {
    if (applying || !model) return
    const draft = history.now
    const { operations, owners } = draftOperations(model, draft)
    if (!operations.length) return
    setApplying(true)
    try {
      const answer = await api.apply(operations)
      const settled = settleApply(draft, owners, answer)
      setBoundaryErrors(errors => ({ ...errors, ...settled.boundaryErrors }))
      setApplyResult(settled.result)
      // What was applied leaves the draft; that isn't a step to undo
      setHistory({ past: [], now: settled.draft, future: [] })
      save(settled.draft)
    } catch (e) {
      setApplyResult({ ok: false, general: [connectionOf(e) === "key" ? tr("页面的链接已经失效") : tr("发送失败，游戏可能已经关闭")], applied: 0, created: [] })
    }
    setApplying(false)
    pollNow.current()
  }, [applying, model, history.now, save])

  const sendNow = useCallback(async (operations: any[]) => {
    try {
      await api.apply(operations)
      pollNow.current()
      return true
    } catch {
      return false
    }
  }, [])

  const value: Store = {
    model, connection, loadedAt, language, draft: history.now, draftLoaded, edit, undo, redo,
    canUndo: history.past.length > 0, canRedo: history.future.length > 0,
    applying, applyResult, boundaryErrors, apply, dismissResult: () => setApplyResult(null), sendNow,
    refresh: () => pollNow.current(),
  }
  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>
}
