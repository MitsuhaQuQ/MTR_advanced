import { useEffect, useState } from "react"
import { useEditor, type EditorState } from "./state/useEditor"
import { draftCount } from "./api/types"
import { tr } from "./i18n"
import { Icon } from "./shell/icons"
import { LAYERS, layerOf, type LayerId, type LayerInfo } from "./shell/layers"
import { DepotsOverview, LinesOverview, NotYetHere } from "./views/Overview"

/** The new editor's shell: top bar, layer rail, the layer's work area, status bar. */
export function App() {
  const editor = useEditor()
  const [layer, setLayer] = useState<LayerId>(() => layerOf(location.hash))
  useEffect(() => {
    const follow = () => setLayer(layerOf(location.hash))
    addEventListener("hashchange", follow)
    return () => removeEventListener("hashchange", follow)
  }, [])
  const open = (id: LayerId) => { if (id !== layer) { history.pushState(null, "", "#/" + id); setLayer(id) } }
  const info = LAYERS.find(l => l.id === layer)!

  return (
    <div className="flex h-full flex-col">
      <TopBar editor={editor} />
      <Banner editor={editor} />
      <div className="flex min-h-0 flex-1">
        <LayerRail layer={layer} open={open} />
        <main className="flex min-h-0 min-w-0 flex-1 flex-col">
          <div className="flex h-8 shrink-0 items-center gap-2 overflow-hidden whitespace-nowrap border-b border-grid bg-bg px-3 text-[12px] text-dim">
            {info.step && <span>{tr("第 {0} 层", info.step)}</span>}
            <span className="text-ink">{tr(info.hint)}</span>
          </div>
          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto p-4">
            <Work layer={info} editor={editor} />
          </div>
        </main>
      </div>
      <StatusBar editor={editor} />
    </div>
  )
}

function Work({ layer, editor }: { layer: LayerInfo; editor: EditorState }) {
  if (!editor.data) return <p className="text-[13px] text-dim">{tr("正在读取…")}</p>
  switch (layer.id) {
    case "lines":
    case "timetable": return <LinesOverview data={editor.data} draft={editor.draft} layer={layer} />
    case "depots": return <DepotsOverview data={editor.data} draft={editor.draft} layer={layer} />
    default: return <NotYetHere layer={layer} />
  }
}

function TopBar({ editor }: { editor: EditorState }) {
  const count = draftCount(editor.draft)
  return (
    <header className="flex h-12 shrink-0 items-center gap-2 border-b border-grid bg-panel px-3">
      <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden className="shrink-0">
        <rect x="1" y="1" width="20" height="20" rx="5" fill="var(--accent)" />
        <path d="M5 15 L9 7 L13 13 L17 6" fill="none" stroke="var(--accent-ink)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <h1 className="m-0 truncate text-[15px] font-bold">{tr("MTR 高级编辑器")}</h1>
      <span className="shrink-0 rounded-pill px-2 text-[12px] font-medium leading-5 tint-accent text-brand">{tr("新版预览")}</span>
      <span className="flex-1" />
      <span className={"flex shrink-0 items-center gap-1.5 text-[13px] " + (count ? "text-sel" : "text-dim")}
        title={count ? tr("草稿和旧版共用；现在请在旧版里查看和应用") : ""}>
        <span className={"h-2 w-2 rounded-full " + (count ? "bg-sel" : "bg-gridstrong")} />
        {count ? tr("草稿 {0} 项", count) : tr("没有草稿")}
      </span>
      <a className="btn" href="../#/timetable" title={tr("回到旧版编辑器，草稿和选择都在")}>
        <Icon name="back" size={14} />{tr("回到旧版")}
      </a>
    </header>
  )
}

function Banner({ editor }: { editor: EditorState }) {
  const text = {
    connecting: "",
    ok: "",
    key: tr("密钥不对：请从游戏里重新打开编辑器。"),
    world: tr("游戏还没进入世界，进入后会自动读取。"),
    offline: tr("连不上游戏：游戏关掉了，或编辑器的服务停了。"),
  }[editor.connection]
  if (!text) return null
  return <div role="status" className="shrink-0 border-b border-grid tint-warn px-3 py-1.5 text-[13px] text-warn">{text}</div>
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

function StatusBar({ editor }: { editor: EditorState }) {
  const [, tick] = useState(0)
  useEffect(() => { const id = setInterval(() => tick(t => t + 1), 1000); return () => clearInterval(id) }, [])
  const ago = editor.loadedAt ? Math.max(0, Math.round((Date.now() - editor.loadedAt) / 1000)) : -1
  const ok = editor.connection === "ok"
  return (
    <footer className="flex h-7 shrink-0 items-center gap-4 overflow-hidden whitespace-nowrap border-t border-grid bg-panel px-3 text-[12px] text-dim">
      <span className="flex items-center gap-1.5">
        <span className={"h-1.5 w-1.5 rounded-full " + (ok ? "bg-brand" : editor.connection === "connecting" ? "bg-gridstrong" : "bg-warn")} />
        {ok ? tr("已连接游戏") : editor.connection === "connecting" ? tr("正在连接…") : tr("没有连接")}
      </span>
      {ago >= 0 && <span>{tr("数据读取于 {0} 秒前", ago)}</span>}
      {editor.data && !editor.data.canEdit && <span className="text-caution">{tr("没有编辑权限，只能看")}</span>}
      <span className="ml-auto hidden md:inline">{tr("新版逐层搬过来；还没搬的在旧版里改")}</span>
    </footer>
  )
}
