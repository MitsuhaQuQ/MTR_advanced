import { useCallback, useEffect, useState } from "react"
import { StoreProvider, useStore } from "./state/store"
import { tr } from "./i18n"
import { Icon } from "./shell/icons"
import { LAYERS, layerOf, type LayerId } from "./shell/layers"
import { ReviewPanel } from "./shell/Review"
import { SyncPanel, useSyncProblems } from "./shell/Sync"
import { Banner } from "./shell/Banner"
import { conflictCount, draftCount } from "./core/draft"
import { LayerWork } from "./views/Layers"
import { Boundary } from "./ui/Boundary"
import { TrackDiagram } from "./views/TrackDiagram"
import { StopsEditor } from "./views/StopsEditor"
import { LegacyMenu, LegacySlot } from "./legacy/Slot"
import { setMediaShown } from "./legacy/media"
import { legacy, useLegacy } from "./legacy/engine"

export function App() {
  return <StoreProvider><Shell /></StoreProvider>
}

/** The new editor's shell: top bar, layer rail, the layer's work area, status bar, and the panels over them. */
function Shell() {
  const store = useStore()
  const [layer, setLayer] = useState<LayerId>(() => layerOf(location.hash))
  const [panel, setPanel] = useState<"review" | "sync" | null>(null)
  useEffect(() => {
    const follow = () => setLayer(layerOf(location.hash))
    addEventListener("hashchange", follow)
    return () => removeEventListener("hashchange", follow)
  }, [])
  const open = (id: LayerId) => { if (id !== layer) { history.pushState(null, "", "#/" + id); setLayer(id) } }
  const closePanel = useCallback(() => setPanel(null), [])
  /** Shows a route's summary timetable, from the lists of changes and problems (openView in the old page). */
  const openRoute = (routeId: string) => {
    const r = legacy.v.data && legacy.v.data.routeById.get(routeId)
    if (!r) return
    open("timetable")
    legacy.fn.openView("table", r.lineKey, r.id)
  }

  // Undo and redo anywhere but in a text field; Ctrl+Enter opens the draft's changes to apply
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).closest("input, textarea, select, [contenteditable]")) return
      const mod = e.ctrlKey || e.metaKey
      if (!mod) return
      const k = e.key.toLowerCase()
      if (k === "z") { e.preventDefault(); if (e.shiftKey) store.redo(); else store.undo() }
      else if (k === "y") { e.preventDefault(); store.redo() }
      else if (e.key === "Enter") { e.preventDefault(); setPanel("review") }
    }
    addEventListener("keydown", onKey)
    return () => removeEventListener("keydown", onKey)
  }, [store])

  const info = LAYERS.find(l => l.id === layer)!
  return (
    <div className="flex h-full flex-col">
      <TopBar onReview={() => setPanel("review")} onSync={() => setPanel("sync")} />
      <Banner onReview={() => setPanel("review")} />
      <div className="flex min-h-0 flex-1">
        <LayerRail layer={layer} open={open} />
        <main className="flex min-h-0 min-w-0 flex-1 flex-col">
          <div className="flex h-8 shrink-0 items-center gap-2 overflow-hidden whitespace-nowrap border-b border-grid bg-bg px-3 text-[12px] text-dim">
            {info.step && <span>{tr("第 {0} 层", info.step)}</span>}
            <span className="text-ink">{tr(info.hint)}</span>
          </div>
          <Boundary resetKey={layer}>{layer === "media" ? <MediaWork /> : <LayerWork layer={layer} />}</Boundary>
        </main>
      </div>
      <StatusBar />
      <ReviewPanel open={panel === "review"} onClose={closePanel} onOpenRoute={openRoute} />
      <SyncPanel open={panel === "sync"} onClose={closePanel} onOpenRoute={openRoute} />
      <LegacyMenu />
      <TrackDiagram />
      <StopsEditor />
    </div>
  )
}

/** The media library: the old one (media.js), with its upload button and usage in a tool bar of the new page. */
function MediaWork() {
  useEffect(() => {
    setMediaShown(true)
    return () => setMediaShown(false)
  }, [])
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center gap-3 border-b border-grid bg-panel px-3 py-2">
        <span className="text-[13px] font-medium">{tr("媒体库")}</span>
        <span className="flex-1" />
        <LegacySlot id="media-usage" className="text-[12px]" />
        <LegacySlot id="media-upload" />
      </div>
      <LegacySlot id="media-app" className="min-h-0 flex-1" />
      <LegacySlot id="media-status" className="shrink-0" />
    </div>
  )
}

function TopBar({ onReview, onSync }: { onReview: () => void; onSync: () => void }) {
  const store = useStore()
  const count = draftCount(store.draft), conflicts = conflictCount(store.draft)
  const problems = useSyncProblems().filter(p => p.level !== "hint")
  const errors = problems.filter(p => p.level === "err").length
  const canApply = !store.applying && count > conflicts && Boolean(store.model?.canEdit)
  return (
    <header className="flex h-12 shrink-0 items-center gap-2 border-b border-grid bg-panel px-3">
      <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden className="shrink-0">
        <rect x="1" y="1" width="20" height="20" rx="5" fill="var(--accent)" />
        <path d="M5 15 L9 7 L13 13 L17 6" fill="none" stroke="var(--accent-ink)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <h1 className="m-0 hidden truncate text-[15px] font-bold sm:block">{tr("MTR 高级编辑器")}</h1>
      <span className="hidden shrink-0 rounded-pill px-2 text-[12px] font-medium leading-5 tint-accent text-brand sm:inline">{tr("新版预览")}</span>
      <span className="flex-1" />
      <Weekday />
      <button className={"btn btn-ghost " + (errors ? "text-warn" : problems.length ? "text-caution" : "text-dim")} onClick={onSync} title={tr("页面和游戏哪里不一致、游戏哪里没做成")}>
        <span className={"h-2 w-2 rounded-full " + (errors ? "bg-warn" : problems.length ? "bg-caution" : "bg-brand")} />
        {problems.length ? tr("同步状态 · {0}", problems.length) : tr("同步状态 · 正常")}
      </button>
      <button className="btn" onClick={onReview} title={count ? tr("点这里看草稿改了哪些地方") : ""}>
        <span className={"h-2 w-2 rounded-full " + (count ? "bg-sel" : "bg-gridstrong")} />
        {store.applying ? tr("正在应用…") : count ? tr("草稿 {0} 项", count) : tr("没有草稿")}
      </button>
      <button className="btn btn-primary" disabled={!canApply} onClick={onReview}
        title={conflicts ? tr("有 {0} 条线路在游戏里被改过，应用时先不发，处理后再应用", conflicts) : tr("把草稿发给游戏")}>{tr("应用")}</button>
      <a className="btn btn-ghost" href="../#/timetable" title={tr("回到旧版编辑器，草稿和选择都在")}>
        <Icon name="back" size={14} /><span className="hidden md:inline">{tr("回到旧版")}</span>
      </a>
    </header>
  )
}

const WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
/** The day the diagrams and tables show (setWeekday in the old page: each day runs its own table). */
function Weekday() {
  useLegacy()
  const { fn, v } = legacy
  if (!v.data) return null
  return (
    <label className="flex shrink-0 items-center gap-1.5 text-[12px] text-dim">
      <span className="hidden lg:inline">{tr("运行日")}</span>
      <select className="h-7 rounded-md border border-ctl bg-panel px-1.5 text-[13px] text-ink" value={v.state.weekday} onChange={e => fn.setWeekday(Number(e.target.value))}>
        {WEEKDAYS.map((day, i) => <option key={day} value={i}>{tr(day)}</option>)}
      </select>
    </label>
  )
}

function LayerRail({ layer, open }: { layer: LayerId; open: (id: LayerId) => void }) {
  return (
    <nav className="flex w-[64px] shrink-0 flex-col gap-1 border-r border-grid bg-panel py-2" aria-label={tr("工作层")}>
      {LAYERS.map((l, i) => {
        const on = l.id === layer
        return (
          <div key={l.id}>
            {i === 5 && <div className="mx-3 my-1 border-t border-grid" />}
            <a href={"#/" + l.id} onClick={e => { e.preventDefault(); open(l.id) }} title={tr(l.hint)} aria-current={on ? "page" : undefined}
              className={"relative mx-1.5 flex w-[52px] flex-col items-center gap-0.5 rounded-md py-1.5 text-[11px] no-underline " + (on ? "tint-accent font-medium text-brand" : "text-dim hover:bg-panel2 hover:text-ink")}>
              {on && <span className="absolute -left-1.5 bottom-1.5 top-1.5 w-[3px] rounded-r-sm bg-brand" />}
              <Icon name={l.icon} />
              <span>{tr(l.name)}</span>
              {l.step && <span className="num absolute right-1 top-0.5 text-[9px] opacity-70">{l.step}</span>}
            </a>
          </div>
        )
      })}
    </nav>
  )
}

function StatusBar() {
  const store = useStore()
  const [, tick] = useState(0)
  useEffect(() => { const id = setInterval(() => tick(t => t + 1), 1000); return () => clearInterval(id) }, [])
  const ago = store.loadedAt ? Math.max(0, Math.round((Date.now() - store.loadedAt) / 1000)) : -1
  const ok = store.connection === "ok"
  return (
    <footer className="flex h-7 shrink-0 items-center gap-4 overflow-hidden whitespace-nowrap border-t border-grid bg-panel px-3 text-[12px] text-dim">
      <span className="flex items-center gap-1.5">
        <span className={"h-1.5 w-1.5 rounded-full " + (ok ? "bg-brand" : store.connection === "connecting" ? "bg-gridstrong" : "bg-warn")} />
        {ok ? tr("已连接游戏") : store.connection === "connecting" ? tr("正在连接…") : tr("没有连接")}
      </span>
      {ago >= 0 && <span>{tr("数据读取于 {0} 秒前", ago)}</span>}
      {store.model && !store.model.canEdit && <span className="text-caution">{tr("没有编辑权限，只能看")}</span>}
      <span className="ml-auto hidden md:inline">{tr("改动先进草稿，按“应用”才发给游戏")}</span>
    </footer>
  )
}
