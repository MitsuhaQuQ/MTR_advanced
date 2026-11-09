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
 * A click that pans the map doesn't paint; Esc or the brush again puts it down. Right-click menus still work.
 */
import { useEffect, useMemo, useSyncExternalStore } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { ConfirmButton } from "../ui/bits"
import { tr } from "../i18n"

export type Brush = { kind: "logical"; id: string | null } | { kind: "owner"; lines: string[] | null; label: string }

/** The brush in hand, shared by the panel, the real map and the logical schematic */
let brushNow: Brush | null = null
const brushListeners = new Set<() => void>()
export function setBrushNow(brush: Brush | null) { brushNow = brush; brushListeners.forEach(listener => listener()) }
export const useBrush = () => useSyncExternalStore(listener => { brushListeners.add(listener); return () => brushListeners.delete(listener) }, () => brushNow)

/** Paints one rail's section with a brush, as the old menus would. */
export function paint(index: number, brush: Brush) {
  const { fn } = legacy
  if (brush.kind === "logical") fn.setRailLogicalLine(index, brush.id)
  else fn.setOwnerOverride(index, brush.lines)
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

const sameBrush = (a: Brush | null, b: Brush) => Boolean(a && a.kind === b.kind && (a.kind === "logical" ? a.id === (b as any).id : JSON.stringify(a.lines) === JSON.stringify((b as any).lines)))

export function OwnershipPanel({ className }: { className: string }) {
  const version = useLegacy()
  const { fn, v } = legacy
  const data = v.data, net = v.net
  const brush = useBrush(), setBrush = setBrushNow
  // Put down when the panel goes
  useEffect(() => () => setBrushNow(null), [])
  const canEdit = Boolean(data?.canEdit)
  const logical = fn.usesLogicalLines() as boolean

  // Painting on the map: a click that didn't pan, on a rail; ahead of the old handlers, which then don't see it
  useEffect(() => {
    const canvas = document.getElementById("net-canvas") as HTMLCanvasElement | null
    if (!canvas || !brush) return
    const onClick = (e: MouseEvent) => {
      if (legacy.v.net.dragged || legacy.v.net.layer !== "owners") return
      const box = canvas.getBoundingClientRect()
      const index = legacy.fn.netRailAt(e.clientX - box.left, e.clientY - box.top)
      if (index < 0) return
      e.stopPropagation()
      paint(index, brush)
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setBrush(null) }
    canvas.addEventListener("click", onClick, true)
    addEventListener("keydown", onKey)
    canvas.style.cursor = "crosshair"
    return () => { canvas.removeEventListener("click", onClick, true); removeEventListener("keydown", onKey); canvas.style.cursor = "" }
  }, [brush])
  // A brush whose logical line is gone is put down
  useEffect(() => {
    if (brush?.kind === "logical" && brush.id !== null && !fn.logicalLinesShown().has(brush.id)) setBrush(null)
  })

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
          <p className="m-0 mb-2 text-[12px] leading-[18px] text-dim">
            {brush ? tr("在图上点轨道：它所在的整段（到下一个道岔或边界）都画进去。拖动照常平移；Esc 放下画笔。") : tr("选一支画笔，再在图上点轨道，一次改一整段。右键菜单照常可用。")}
          </p>
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {logical ? <>
              {logicalLines.map((line: any) => brushRow("l" + line.id, { kind: "logical", id: line.id }, fn.colorOf(line.color), line.name, (
                <span className="flex shrink-0 items-center gap-1">
                  {line.id in drafted && <span className="h-1.5 w-1.5 rounded-full bg-caution" title={tr("有没应用的草稿")} />}
                  {line.connecting && <span className="text-[11px] text-caution">{tr("联络线")}</span>}
                  <span className="num text-[11px] text-dim" title={tr("区段数")}>{counts.get(line.id) || 0}</span>
                  <button type="button" className="btn btn-ghost h-6 px-1 text-[12px]" aria-pressed={net.focusLogical === line.id} title={tr("只看这条")}
                    onClick={() => { net.focusLogical = net.focusLogical === line.id ? null : line.id; fn.drawNetwork(); fn.renderAll() }}>◎</button>
                  <button type="button" className="btn btn-ghost h-6 px-1 text-[12px]" disabled={!canEdit} onClick={() => fn.logicalLineDialog(line)} title={tr("改名、改颜色、设为联络线")}>✎</button>
                  <ConfirmButton className="btn btn-ghost h-6 px-1 text-[12px]" disabled={!canEdit} confirm={tr("删除“{0}”？", line.name)} onConfirm={() => removeLogicalLine(line.id)}>✕</ConfirmButton>
                </span>
              ), line.connecting))}
              {brushRow("erase", { kind: "logical", id: null }, null, tr("橡皮：移出逻辑线路"))}
            </> : <>
              {operating.map((line: any) => brushRow("o" + line.key, { kind: "owner", lines: [line.key], label: line.key }, fn.colorOf(line.color), tr("只归 {0}", fn.shortName(line.name))))}
              {brushRow("open", { kind: "owner", lines: [], label: "open" }, null, tr("对所有线路开放"))}
              {brushRow("auto", { kind: "owner", lines: null, label: "auto" }, null, tr("恢复自动归属"))}
            </>}
          </ul>
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
                  <span className="mb-1 inline-block h-2 w-2 rounded-full" style={{ background: fn.colorOf(line.color) }} /> {line.name}
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
                  <span className="mr-1 inline-block h-2 w-2 rounded-full" style={{ background: fn.colorOf(line.color) }} />
                  {fn.shortName(line.name)}
                  {line.key in draftedTracks && <span className="ml-1 inline-block h-1.5 w-1.5 rounded-full bg-caution" title={tr("有没应用的草稿")} />}
                  {!chosen.length && <span className="ml-1 text-[11px] text-dim">{tr("按站台")}</span>}
                </th>
                {logicalLines.map(logicalLine => (
                  <td key={logicalLine.id} className="text-center">
                    <input type="checkbox" disabled={!canEdit} checked={chosen.includes(logicalLine.id)} aria-label={tr("{0} 走 {1}", fn.shortName(line.name), logicalLine.name)}
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
