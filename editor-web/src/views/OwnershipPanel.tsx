/**
 * The ownership layer's panel beside the network map: brushes instead of a menu per rail, and the operating lines'
 * logical lines as one table instead of a dialog per line.
 *
 * With logical lines: each is a brush (its colour, how many sections it has); choose one and click rails on the map, and
 * each rail's whole section goes into it (old setRailLogicalLine); the eraser takes sections out. Below, a table of the
 * operating lines against the logical lines they may run on (old lineTracks; none ticked: those of their platforms).
 * Without: the brushes are the operating lines (the section owned by that line alone), "open to every line", and "worked
 * out again" (old setOwnerOverride), with the first draft of logical lines a click away.
 *
 * Many sections at once (0.83.13): Shift+click a second rail paints the way between it and the rail clicked before, a
 * drag paints every rail passed over, Shift+drag a box paints every rail in it, and a double click paints along the rail
 * both ways up to the turnouts. Each is one step of undo. Right-drag or Space+drag pans while a brush is in hand, the
 * wheel zooms; Esc drops the way's start, then puts the brush down. Right-click menus still work.
 *
 * Choosing by hand (0.99.0, user 2026-11-17: 人工多选加入分组): the "select" tool takes sections into a choice with the
 * same gestures instead of painting them (a click takes a section in or out), marked on both maps; the choice then goes
 * into a logical line picked below, a new one, or out of its logical line, at once.
 */
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { railPath, railsAlong, railsInBox, railsNearSegment } from "../core/railPick"
import { ConfirmButton } from "../ui/bits"
import { tr } from "../i18n"
import { colorOf, shortName } from "./now"

export type Brush = { kind: "logical"; id: string | null } | { kind: "owner"; lines: string[] | null; label: string } | { kind: "select" }

/** The brush in hand, shared by the panel, the real map and the logical schematic */
let brushNow: Brush | null = null
const brushListeners = new Set<() => void>()
export function setBrushNow(brush: Brush | null) { brushNow = brush; brushListeners.forEach(listener => listener()) }
export const useBrush = () => useSyncExternalStore(listener => { brushListeners.add(listener); return () => brushListeners.delete(listener) }, () => brushNow)

/** The rails chosen by hand with the select tool (0.99.0), each with its whole section */
let selection: Set<number> = new Set()
const selectionListeners = new Set<() => void>()
export const useSelection = () => useSyncExternalStore(listener => { selectionListeners.add(listener); return () => selectionListeners.delete(listener) }, () => selection)
/** The rails of the sections of these rails */
const sectionRails = (indices: Iterable<number>) => {
  const rails = new Set<number>()
  for (const index of indices) for (const each of legacy.v.sectionOf(index)) rails.add(each)
  return rails
}
function setSelection(next: Set<number>) {
  selection = next
  const net = legacy.v.net
  if (net) net.paintMarks = { rails: [...next], box: null, start: net.paintMarks?.start ?? -1 }
  legacy.fn.drawNetwork()
  selectionListeners.forEach(listener => listener())
}
/** Takes rails' sections into the choice, or with toggle, a section chosen already out of it */
export function choose(indices: number[], toggle: boolean) {
  const next = new Set(selection)
  const rails = sectionRails(indices)
  if (toggle && [...rails].every(rail => next.has(rail))) rails.forEach(rail => next.delete(rail))
  else rails.forEach(rail => next.add(rail))
  setSelection(next)
}
export function clearSelection() { setSelection(new Set()) }

/** Paints one rail's section with a brush, as the old menus would; the select tool takes it in or out of the choice. */
export function paint(index: number, brush: Brush) {
  const { fn } = legacy
  if (brush.kind === "select") choose([index], true)
  else if (brush.kind === "logical") fn.setRailLogicalLine(index, brush.id)
  else fn.setOwnerOverride(index, brush.lines)
}

/** Paints many rails' sections with a brush at once, each section once: one step of undo (0.83.13); the select tool takes them into the choice. */
export function paintMany(indices: number[], brush: Brush) {
  if (!indices.length) return
  const { fn } = legacy
  if (brush.kind === "select") choose(indices, false)
  else if (brush.kind === "logical") fn.setRailsLogicalLine(indices, brush.id)
  else fn.setRailsOwnerOverride(indices, brush.lines)
}

/** Whether a key goes to something typed into, where Space is a space */
const typing = (target: EventTarget | null) => target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))

type Gesture = { kind: "stroke" | "box" | "pan"; x: number; z: number; px: number; pz: number; started: boolean; last: number[]; rails: Set<number> }

/**
 * The brush's gestures on the real map (0.83.13), ahead of the old handlers: a click paints a section and is the start of
 * a way, Shift+click paints the way to it, a drag paints what it passes over, Shift+drag a box, a double click along the
 * rail to the turnouts. Left-drag paints, so right-drag and Space+drag pan. What a drag or a box will paint is marked on
 * the map (`net.paintMarks`, drawn by the old code) and painted when the button is let go.
 */
function useBrushGestures(brush: Brush | null, start: { current: number }, setStart: (index: number) => void) {
  useEffect(() => {
    const canvas = document.getElementById("net-canvas") as HTMLCanvasElement | null
    if (!canvas || !brush) return
    const { v, fn } = legacy
    const net = () => v.net
    const on = () => net().layer === "owners" && Boolean(net().data && net().railPoints)
    const local = (e: MouseEvent) => { const box = canvas.getBoundingClientRect(); return [e.clientX - box.left, e.clientY - box.top] }
    const world = (px: number, pz: number) => [v.netWorldX(px), v.netWorldZ(pz)]
    let space = false, swallowClick = false, rightMoved = false
    let gesture: Gesture | null = null
    const cursor = () => { canvas.style.cursor = space || gesture?.kind === "pan" ? "grab" : "crosshair" }
    /** Marks the sections of the rails a gesture will paint, the start of a way, and the box */
    const mark = (rails: Iterable<number> = [], box: { x0: number; z0: number; x1: number; z1: number } | null = null) => {
      // The choice so far stays marked while the select tool takes more
      const shown = new Set<number>(brush.kind === "select" ? selection : [])
      for (const index of rails) for (const each of v.sectionOf(index)) shown.add(each)
      net().paintMarks = { rails: [...shown], start: start.current, box }
      fn.drawNetwork()
    }
    // A brush taken up starts without a way's start
    setStart(-1)

    const onMove = (e: MouseEvent) => {
      const now = gesture
      if (!now) return
      if (!now.started) {
        if (Math.hypot(e.clientX - now.x, e.clientY - now.z) <= 4) return
        now.started = true
      }
      if (now.kind === "pan") { rightMoved = true; return }
      const [px, pz] = local(e), [x, z] = world(px, pz)
      if (now.kind === "stroke") {
        const reach = v.NET_HIT_PX / net().scale
        railsNearSegment(net().railPoints, now.last[0], now.last[1], x, z, reach).forEach(index => now.rails.add(index))
        now.last = [x, z]
        mark(now.rails)
      } else {
        const [x0, z0] = world(now.px, now.pz)
        now.rails = new Set(railsInBox(net().railPoints, x0, z0, x, z))
        mark(now.rails, { x0: now.px, z0: now.pz, x1: px, z1: pz })
      }
    }
    const onUp = () => {
      removeEventListener("mousemove", onMove)
      removeEventListener("mouseup", onUp)
      const done = gesture
      gesture = null
      cursor()
      if (!done || done.kind === "pan" || !done.started) return
      // The click that ends the drag isn't one
      swallowClick = true
      setTimeout(() => { swallowClick = false })
      mark()
      paintMany([...done.rails], brush)
    }
    const onDown = (e: MouseEvent) => {
      swallowClick = false
      if (!on()) return
      const [px, pz] = local(e)
      if (e.button === 2) {
        // Panned as the old left-drag does: the old mousemove pans while there is a drag
        rightMoved = false
        net().drag = { x: e.clientX, z: e.clientY, cx: net().cx, cz: net().cz }
        gesture = { kind: "pan", x: e.clientX, z: e.clientY, px, pz, started: false, last: [], rails: new Set() }
        cursor()
      } else if (e.button === 0 && !space) {
        // Not a pan: the old handler doesn't see it
        e.stopPropagation()
        e.preventDefault()
        gesture = { kind: e.shiftKey ? "box" : "stroke", x: e.clientX, z: e.clientY, px, pz, started: false, last: world(px, pz), rails: new Set() }
      } else return
      addEventListener("mousemove", onMove)
      addEventListener("mouseup", onUp)
    }
    const onClick = (e: MouseEvent) => {
      if (swallowClick) { swallowClick = false; e.stopPropagation(); return }
      if (net().dragged || !on()) return
      const [px, pz] = local(e)
      const index = fn.netRailAt(px, pz)
      if (index < 0) return
      e.stopPropagation()
      if (e.shiftKey && start.current >= 0) {
        const way = railPath(net().data.rails, net().railPoints, start.current, index)
        paintMany(way.length ? way : [index], brush)
      } else paint(index, brush)
      // The end of a way is the start of the next
      setStart(index)
    }
    const onDouble = (e: MouseEvent) => {
      if (!on()) return
      const [px, pz] = local(e)
      const index = fn.netRailAt(px, pz)
      if (index < 0) return
      e.stopPropagation()
      paintMany(railsAlong(net().data.rails, index), brush)
    }
    const onMenu = (e: MouseEvent) => {
      // A right-drag that panned opens no menu
      if (!rightMoved) return
      rightMoved = false
      e.preventDefault()
      e.stopPropagation()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (start.current >= 0) setStart(-1)
        else setBrushNow(null)
      } else if (e.key === " " && !typing(e.target)) {
        e.preventDefault()
        if (!space) { space = true; cursor() }
      }
    }
    const onKeyUp = (e: KeyboardEvent) => {
      if (e.key !== " " || typing(e.target)) return
      e.preventDefault()
      space = false
      cursor()
    }
    const onBlur = () => { space = false; cursor() }
    canvas.addEventListener("mousedown", onDown, true)
    canvas.addEventListener("click", onClick, true)
    canvas.addEventListener("dblclick", onDouble, true)
    canvas.addEventListener("contextmenu", onMenu, true)
    addEventListener("keydown", onKey)
    addEventListener("keyup", onKeyUp)
    addEventListener("blur", onBlur)
    cursor()
    return () => {
      canvas.removeEventListener("mousedown", onDown, true)
      canvas.removeEventListener("click", onClick, true)
      canvas.removeEventListener("dblclick", onDouble, true)
      canvas.removeEventListener("contextmenu", onMenu, true)
      removeEventListener("keydown", onKey)
      removeEventListener("keyup", onKeyUp)
      removeEventListener("blur", onBlur)
      removeEventListener("mousemove", onMove)
      removeEventListener("mouseup", onUp)
      canvas.style.cursor = ""
      start.current = -1
      // The choice stays marked while it lasts
      net().paintMarks = selection.size ? { rails: [...selection], box: null, start: -1 } : null
      fn.drawNetwork()
    }
  }, [brush]) // eslint-disable-line react-hooks/exhaustive-deps
}

/** Takes a logical line away with its sections and the operating lines' choices of it (old deleteLogicalLine, unasked). */
export function removeLogicalLine(id: string) {
  const { fn } = legacy
  fn.draftSettingsPart("railLines", (drafted: any, saved: any) => {
    Object.entries(fn.railLinesShown()).forEach(([key, line]) => {
      if (line !== id) return
      if (key in saved) drafted[key] = null
      else delete drafted[key]
    })
  })
  fn.draftSettingsPart("lineTracks", (drafted: any, saved: any) => {
    Object.entries({ ...saved, ...drafted }).forEach(([lineKey, ids]: [string, any]) => {
      // None left is the same as none set: the line runs on its platforms' logical lines
      if (ids && ids.includes(id)) drafted[lineKey] = ids.length > 1 ? ids.filter((each: string) => each !== id) : null
    })
  })
  fn.draftSettingsPart("logicalLines", (drafted: any) => { drafted[id] = null })
}

/** Ticks or unticks a logical line for an operating line. */
export function setLineTrack(lineKey: string, id: string, on: boolean) {
  const { fn } = legacy
  const tracks = { ...(fn.savedSettings().lineTracks || {}), ...(fn.draftSettings().lineTracks || {}) }
  const chosen: string[] = (tracks[lineKey] || []).filter((each: string) => each !== id)
  if (on) chosen.push(id)
  fn.draftSettingsPart("lineTracks", (drafted: any) => { drafted[lineKey] = chosen.length ? chosen : null })
}

const sameBrush = (a: Brush | null, b: Brush) => Boolean(a && a.kind === b.kind && (a.kind === "select" || (a.kind === "logical" ? a.id === (b as any).id : JSON.stringify(a.lines) === JSON.stringify((b as any).lines))))

export function OwnershipPanel({ className }: { className: string }) {
  const version = useLegacy()
  const { fn, v } = legacy
  const data = v.data, net = v.net
  const brush = useBrush(), setBrush = setBrushNow
  const chosen = useSelection()
  const [target, setTarget] = useState("")
  // The start of a way between two rails: the rail clicked last
  const start = useRef(-1), [hasStart, setHasStart] = useState(false)
  const setStart = (index: number) => {
    start.current = index
    setHasStart(index >= 0)
    legacy.v.net.paintMarks = { rails: [], box: null, ...legacy.v.net.paintMarks, start: index }
    legacy.fn.drawNetwork()
  }
  useBrushGestures(brush, start, setStart)
  useEffect(() => { if (!brush) setHasStart(false) }, [brush])
  // Put down when the panel goes
  useEffect(() => () => setBrushNow(null), [])
  const canEdit = Boolean(data?.canEdit)
  const logical = fn.usesLogicalLines() as boolean

  // A brush whose logical line is gone is put down
  useEffect(() => {
    if (brush?.kind === "logical" && brush.id !== null && !fn.logicalLinesShown().has(brush.id)) setBrush(null)
  })

  // How many sections are chosen by hand
  const chosenSections = useMemo(() => {
    const sections = new Set<unknown>()
    chosen.forEach(rail => { const entry = net.ownerOf && net.ownerOf.get(rail); sections.add(entry ? entry.section : "r" + rail) })
    return sections.size
  }, [chosen]) // eslint-disable-line react-hooks/exhaustive-deps

  // How many sections each logical line has, as the draft has them
  const counts = useMemo(() => {
    const result = new Map<string, number>()
    if (!logical || !net.sectionRails) return result
    net.sectionRails.forEach((rails: number[]) => {
      const line = fn.railLogicalLine(rails[0])
      if (line) result.set(line.id, (result.get(line.id) || 0) + 1)
    })
    return result
  }, [version, logical]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return null
  const mode = net.mode || "TRAIN"
  const operating = data.lines.filter((line: any) => line.key.startsWith(mode + "|"))
  const logicalLines = [...fn.logicalLinesShown().values()].sort((a: any, b: any) => a.name.localeCompare(b.name))
  const drafted = fn.draftSettings().logicalLines || {}
  const ready = Boolean(net.data && net.data.owners && net.data.owners.ready)

  const brushRow = (key: string, b: Brush, swatch: string | null, label: string, extra?: React.ReactNode, dashed?: boolean) => {
    const on = sameBrush(brush, b)
    return (
      <li key={key} className={"flex items-center gap-2 rounded-md px-1.5 py-1 " + (on ? "tint-sel" : "")}>
        <button type="button" className="flex min-w-0 flex-1 items-center gap-2 text-left text-[13px]" aria-pressed={on} disabled={!canEdit}
          onClick={() => setBrush(on ? null : b)} title={on ? tr("再点一下放下画笔") : tr("选它当画笔，再在图上点轨道")}>
          <span className="h-[4px] w-5 shrink-0 rounded-pill" style={swatch ? { background: swatch, ...(dashed ? { backgroundImage: "repeating-linear-gradient(90deg, transparent 0 5px, var(--panel) 5px 8px)" } : {}) } : { border: "1px dashed var(--grid-strong)" }} />
          <span className={"min-w-0 truncate " + (on ? "font-semibold" : "")}>{label}</span>
        </button>
        {extra}
      </li>
    )
  }

  return (
    <aside className={className} aria-label={tr("归属画笔")}>
      <div className="flex flex-col gap-3 p-3">
        <section>
          <h3 className="label-sm m-0 mb-1">{logical ? tr("逻辑线路画笔") : tr("归属画笔")}</h3>
          {brush ? (
            <ul className="m-0 mb-2 list-disc pl-4 text-[12px] leading-[18px] text-dim">
              <li>{tr("点轨道：画它所在的整段（到下一个道岔或边界）。")}</li>
              <li className={hasStart ? "font-semibold text-ink" : ""}>{hasStart ? tr("起点已选（蓝色虚线）：Shift+点另一段，画两段之间最短的一条路；终点又是下一段路的起点。") : tr("先点一段当起点，再 Shift+点另一段：画两段之间最短的一条路。")}</li>
              <li>{tr("按住左键拖：经过的轨道都画上。")}</li>
              <li>{tr("Shift+拖出方框：框里和压到框边的轨道都画上。")}</li>
              <li>{tr("双击：顺着轨道往两头画，到道岔或轨道尽头为止。")}</li>
              <li>{tr("右键拖或按住空格拖：平移；滚轮缩放。每个动作撤销一次就退回。Esc 先取消起点，再放下画笔。")}</li>
            </ul>
          ) : (
            <p className="m-0 mb-2 text-[12px] leading-[18px] text-dim">{tr("选一支画笔，再在图上点轨道，一次改一整段；也能连路、拖动、框选、双击成片地画。右键菜单照常可用。")}</p>
          )}
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {logical ? <>
              {logicalLines.map((line: any) => brushRow("l" + line.id, { kind: "logical", id: line.id }, colorOf(line.color), line.name, (
                <span className="flex shrink-0 items-center gap-1">
                  {line.id in drafted && <span className="h-1.5 w-1.5 rounded-full bg-caution" title={tr("有没应用的草稿")} />}
                  {line.connecting && <span className="text-[11px] text-caution">{tr("联络线")}</span>}
                  <span className="num text-[11px] text-dim" title={tr("区段数")}>{counts.get(line.id) || 0}</span>
                  <button type="button" className="btn btn-ghost h-6 px-1 text-[12px]" aria-pressed={net.focusLogical === line.id} title={tr("只看这条")}
                    onClick={() => { net.focusLogical = net.focusLogical === line.id ? null : line.id; fn.drawNetwork(); fn.renderAll() }}>◎</button>
                  <button type="button" className="btn btn-ghost h-6 px-1 text-[12px]" disabled={!canEdit} onClick={() => fn.logicalLineDialog(line)} title={tr("改名、改颜色")}>✎</button>
                  <ConfirmButton className="btn btn-ghost h-6 px-1 text-[12px]" disabled={!canEdit} confirm={tr("删除“{0}”？", line.name)} onConfirm={() => removeLogicalLine(line.id)}>✕</ConfirmButton>
                </span>
              ), line.connecting))}
              {brushRow("erase", { kind: "logical", id: null }, null, tr("橡皮：移出逻辑线路"))}
              {brushRow("select", { kind: "select" }, null, tr("多选：先选区段，再加入逻辑线路"))}
            </> : <>
              {operating.map((line: any) => brushRow("o" + line.key, { kind: "owner", lines: [line.key], label: line.key }, colorOf(line.color), tr("只归 {0}", shortName(line.name))))}
              {brushRow("open", { kind: "owner", lines: [], label: "open" }, null, tr("对所有线路开放"))}
              {brushRow("auto", { kind: "owner", lines: null, label: "auto" }, null, tr("恢复自动归属"))}
              {brushRow("select", { kind: "select" }, null, tr("多选：先选区段，再加入逻辑线路"))}
            </>}
          </ul>
          {canEdit && chosen.size > 0 && (
            <div className="mt-2 flex flex-col gap-1.5 rounded-md border border-ctl p-2" role="group" aria-label={tr("选中的区段")} data-chosen-sections={chosenSections}>
              <span className="text-[12px] font-semibold">{tr("已选 {0} 段", chosenSections)}</span>
              {logical && (
                <div className="flex items-center gap-1.5">
                  <select className="h-7 min-w-0 flex-1 rounded-md border border-ctl bg-panel px-1 text-[13px]" value={target} onChange={e => setTarget(e.target.value)} aria-label={tr("加入哪条逻辑线路")}>
                    <option value="">{tr("选逻辑线路…")}</option>
                    {logicalLines.map((line: any) => <option key={line.id} value={line.id}>{line.name}</option>)}
                  </select>
                  <button className="btn" disabled={!target} onClick={() => { paintMany([...chosen], { kind: "logical", id: target }); clearSelection() }}>{tr("加入")}</button>
                </div>
              )}
              <div className="flex flex-wrap gap-1.5">
                <button className="btn" onClick={() => fn.logicalLineDialog(null, (id: string) => { paintMany([...chosen], { kind: "logical", id }); clearSelection() })}>{tr("新建逻辑线路并加入…")}</button>
                {logical && <button className="btn" onClick={() => { paintMany([...chosen], { kind: "logical", id: null }); clearSelection() }}>{tr("移出逻辑线路")}</button>}
                <button className="btn btn-ghost" onClick={clearSelection}>{tr("清空选择")}</button>
              </div>
            </div>
          )}
          {canEdit && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              <button className="btn" onClick={() => fn.logicalLineDialog(null, (id: string) => setBrush({ kind: "logical", id }))}>{tr("＋ 新建逻辑线路…")}</button>
              {!logical && <button className="btn" disabled={!ready} title={ready ? "" : tr("全网配线的归属还没从服务端读来")} onClick={() => fn.logicalDraftDialog()}>{tr("按现在的归属生成初稿…")}</button>}
            </div>
          )}
        </section>
        {logical && (
          <section>
            <h3 className="label-sm m-0 mb-1">{tr("运营线路走哪些逻辑线路")}</h3>
            <p className="m-0 mb-2 text-[12px] leading-[18px] text-dim">{tr("都不勾时，走它停靠的站台所在的逻辑线路。联络线和不属于任何逻辑线路的轨道谁都能走。")}</p>
            <LineTracks operating={operating} logicalLines={logicalLines.filter((line: any) => !line.connecting)} canEdit={canEdit} />
          </section>
        )}
      </div>
    </aside>
  )
}

function LineTracks({ operating, logicalLines, canEdit }: { operating: any[]; logicalLines: any[]; canEdit: boolean }) {
  const { fn } = legacy
  if (!logicalLines.length) return <p className="m-0 text-[12px] text-dim">{tr("还没有逻辑线路")}</p>
  const tracks = { ...(fn.savedSettings().lineTracks || {}), ...(fn.draftSettings().lineTracks || {}) }
  const draftedTracks = fn.draftSettings().lineTracks || {}
  return (
    <div className="scroll-thin overflow-x-auto">
      <table className="border-collapse text-[12px]">
        <thead>
          <tr>
            <th className="sticky left-0 bg-panel" />
            {logicalLines.map(line => (
              <th key={line.id} className="h-24 w-7 align-bottom font-normal" title={line.name}>
                <span className="inline-block max-h-24 truncate whitespace-nowrap" style={{ writingMode: "vertical-rl" }}>
                  <span className="mb-1 inline-block h-2 w-2 rounded-full" style={{ background: colorOf(line.color) }} /> {line.name}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {operating.map(line => {
            const chosen: string[] = tracks[line.key] || []
            return (
              <tr key={line.key} className="border-t border-grid">
                <th scope="row" className="sticky left-0 max-w-[120px] truncate bg-panel py-1 pr-2 text-left font-normal">
                  <span className="mr-1 inline-block h-2 w-2 rounded-full" style={{ background: colorOf(line.color) }} />
                  {shortName(line.name)}
                  {line.key in draftedTracks && <span className="ml-1 inline-block h-1.5 w-1.5 rounded-full bg-caution" title={tr("有没应用的草稿")} />}
                  {!chosen.length && <span className="ml-1 text-[11px] text-dim">{tr("按站台")}</span>}
                </th>
                {logicalLines.map(logicalLine => (
                  <td key={logicalLine.id} className="text-center">
                    <input type="checkbox" disabled={!canEdit} checked={chosen.includes(logicalLine.id)} aria-label={tr("{0} 走 {1}", shortName(line.name), logicalLine.name)}
                      onChange={e => setLineTrack(line.key, logicalLine.id, e.target.checked)} />
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
