/**
 * The summary timetable, drawn by the new page (rewrite step 3): a route's table (its tables for the day, the least
 * performance, adding services, a column a service with its stops' arrivals and departures), or a line's routes together
 * one way. The cells are worked out as the old timeCell / terminalCell / serviceHead did and keep their classes (and so
 * their looks); a cell is edited in place with an input of the page's own (Enter: this service, Shift+Enter: this and the
 * later ones, Tab: the next cell, Esc: leave it). The rules are the old functions: changeTime, typedTime, cellMenu,
 * toggleChosen, chooseRange, clearTable, editTimetable, setManaged, servicesToAdd and Batch.fitSpan.
 *
 * The old table still renders into its own hidden element: its deadheadIndex (the runs out and back shown in the heads)
 * is worked out there, and the "old drawing" switch shows it again.
 */
import { useEffect, useRef, useState, type ReactNode } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { tr } from "../i18n"
import { DAY, clock, colorOf, currentLine, daysText, findService, mod, orderDepotName, parseClock, routeTitle, shortName, tableName, timesOf, trc, typedTime, weekdayName, weekdaysShort } from "./now"

type Editing = { svc: string; stop: number; kind: string; value: string; old: number; bad: boolean }

export function SummaryTable({ className }: { className: string }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const root = useRef<HTMLDivElement>(null)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [asking, setAsking] = useState<{ text: string; go: () => void } | null>(null)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => { if (editing && input.current && document.activeElement !== input.current) { input.current.focus(); input.current.select() } }, [editing?.svc, editing?.stop, editing?.kind]) // eslint-disable-line react-hooks/exhaustive-deps
  // The chosen service's column in view, as the old table did
  useEffect(() => {
    const head = root.current?.querySelector("th.svc-head.hl")
    if (head && !state.selectedRoute && (head as any).scrollIntoView) (head as HTMLElement).scrollIntoView({ block: "nearest", inline: "center" })
  }, [state.selected]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!data || !fn.showsTable()) return null
  const line = currentLine()
  const lineMode = !state.route && line && line.routes.length > 1
  const r = lineMode ? null : state.route ? data.routeById.get(state.route) : line && line.routes[0]

  // ---------- Editing a cell ----------
  const startEditing = (cell: HTMLElement) => {
    const s = findService(cell.dataset.svc || ""), stop = Number(cell.dataset.stop), kind = cell.dataset.kind || ""
    if (!s || kind === "derived" || (kind === "pass" && cell.textContent === "レ")) return
    const t = timesOf(s)[stop], old = kind === "dep" ? t[1] : t[0]
    setEditing({ svc: s.id, stop, kind, value: clock(old, true), old, bad: false })
  }
  const commit = (move: number, toLater: boolean) => {
    if (!editing) return
    const s = findService(editing.svc)
    const time = typedTime(editing.value, editing.old)
    if (time === null) { setEditing({ ...editing, bad: true }); return }
    const cells = [...(root.current?.querySelectorAll("td") || [])] as HTMLElement[]
    const index = cells.findIndex(c => c.dataset.svc === editing.svc && Number(c.dataset.stop) === editing.stop && c.dataset.kind === editing.kind)
    setEditing(null)
    if (s && Math.abs(time - editing.old) >= 1000) fn.changeTime(s, editing.stop, editing.kind, time, toLater)
    if (move && index >= 0) {
      // The next cell once the table is drawn again
      setTimeout(() => {
        const next = ([...(root.current?.querySelectorAll("td") || [])] as HTMLElement[])[index + move]
        if (next && next.dataset.svc) startEditing(next)
      })
    }
  }
  const editor = (cell: { svc: string; stop: number; kind: string }) => editing && editing.svc === cell.svc && editing.stop === cell.stop && editing.kind === cell.kind ? (
    <input ref={input} value={editing.value} aria-label={tr("时刻")} title={tr("回车只改这一班，Shift+回车把同样的改动用到这一班和之后的全部班次")}
      style={editing.bad ? { borderColor: "var(--warn)" } : undefined}
      onChange={e => setEditing({ ...editing, value: e.target.value, bad: false })}
      onKeyDown={e => {
        e.stopPropagation()
        if (e.key === "Enter") { e.preventDefault(); commit(0, e.shiftKey) }
        else if (e.key === "Tab") { e.preventDefault(); commit(e.shiftKey ? -1 : 1, false) }
        else if (e.key === "Escape") setEditing(null)
      }}
      onBlur={() => commit(0, false)} onClick={e => e.stopPropagation()} />
  ) : null

  // ---------- The cells (old timeCell, terminalCell, serviceHead) ----------
  const cellProps = (s: any, i: number, kind: string, t: number[], selected: boolean): { className: string; text: ReactNode; title?: string; data?: { svc: string; stop: number; kind: string } } => {
    if ((kind === "arr" && i === 0) || (kind === "dep" && i === s.route.stops.length - 1)) {
      const pairs = fn.terminalNow(), pairing = kind === "arr" ? pairs.previous.get(s.id) : pairs.next.get(s.id)
      const [firstServed, lastServed] = fn.servedStops(s)
      if ((kind === "arr" && firstServed > 0) || (kind === "dep" && lastServed < s.route.stops.length - 1)) return { className: "pass unserved" + (selected ? " hl" : ""), text: "—" }
      const time = pairing ? (kind === "arr" ? pairing.toArrival : pairing.fromDeparture) : kind === "arr" ? t[0] : t[1]
      const title = pairing ? fn.terminalText(pairing) + "\n" + (kind === "arr" ? tr("跟着上一班，改那一班的时刻") : tr("跟着下一班，改那一班的时刻")) : tr("没有接续的班次：只算本班的停站时间")
      const classes = ["derived", selected ? "hl" : "", pairing && pairing.source === "nearest" ? "inferred" : "", pairing && pairing.conflict ? "fast" : "", pairing && pairing.kind !== "same" ? "out" : ""].filter(Boolean).join(" ")
      return { className: classes, text: clock(time, true), title, data: { svc: s.id, stop: i, kind: "derived" } }
    }
    const plan = s.plans[i], exception = s.exceptions && s.exceptions.get(i)
    const [firstServed, lastServed] = fn.servedStops(s)
    if (i < firstServed || i > lastServed || (i === firstServed && kind === "arr" && i > 0) || (i === lastServed && kind === "dep" && i < s.route.stops.length - 1)) {
      const before = i < firstServed || (i === firstServed && kind === "arr")
      return { className: ["pass", "unserved", selected ? "hl" : ""].filter(Boolean).join(" "), text: "—", title: before ? tr("出车 ← {0}", orderDepotName(s.deployOrder)) : tr("回送 → {0}", orderDepotName(s.returnOrder)), data: { svc: s.id, stop: i, kind } }
    }
    const passes = kind !== "pass" && plan.passes
    const changed = (exception && (kind === "dep" ? exception.dwell >= 0 : exception.run >= 0)) || (exception && exception.pass >= 0)
    const leaves = s.link && kind !== "dep" && (s.link.fromStop < 0 ? i === s.route.stops.length - 1 : s.link.fromStop === i)
    const picked = state.pendingExit && state.pendingExit.id === s.id && state.pendingExit.index === i && kind !== "dep"
    const classes = [selected ? "hl" : "", kind === "pass" || passes ? "pass" : "", changed ? "exc" : "", kind !== "dep" && plan.tooFast ? "fast" : "", kind === "dep" && plan.overtake ? "wait" : "", exception && exception.pass >= 0 ? "pending-run" : "", leaves ? "out" : "", picked ? "pending" : ""].filter(Boolean).join(" ")
    const title = exception && exception.pass >= 0 ? tr("只改了这一班的时刻：列车实际仍按线路的停站运行，按班次生成路径后（E2）才真正通过或停车") : plan.tooFast && kind !== "dep" ? tr("快过最快时刻，列车跑不到") : undefined
    const text = passes ? (kind === "arr" ? "↓ " + clock(t[0], true) : "レ") : clock(kind === "dep" ? t[1] : t[0], true)
    return { className: classes, text, title, data: { svc: s.id, stop: i, kind: passes ? "pass" : kind } }
  }
  // Plain functions, not components: a component made in here would be a new type each time, and every cell made again
  const cellOf = (s: any, i: number, kind: string, t: number[], selected: boolean) => {
    const c = cellProps(s, i, kind, t, selected)
    if (!c.data) return <td key={s.id} className={c.className}>{c.text}</td>
    return <td key={s.id} className={c.className} title={c.title} data-svc={c.data.svc} data-stop={c.data.stop} data-kind={c.data.kind} tabIndex={0}>{editor(c.data) || c.text}</td>
  }
  const headOf = (s: any, selected: boolean, note: ReactNode) => {
    const noTrain = !s.suspended && fn.hasNoTrain(s)
    const deadheads = v.showDeadheads && v.deadheadIndex && v.deadheadIndex.get(s.id)
    const time = editor({ svc: s.id, stop: 0, kind: "dep" }) || <>{clock(s.start)}{s.exceptions && <span className="exc-mark" title={tr("有只改这一班的例外")}> *</span>}</>
    return (
      <th key={s.id} className={"svc-head" + (selected ? " hl" : "") + (noTrain ? " no-train" : "") + (s.suspended ? " suspended" : "")} data-svc={s.id} data-stop={0} data-kind="dep"
        title={(noTrain ? tr("没有车跑这一班") + "\n" : "") + (s.suspended ? tr("停运：留在表里，不出车、不排运用，右键恢复") + "\n" : "") + tr("点这里改班次时刻；右键更多")}>
        <span className="time">{time}</span>{note}
        {deadheads && deadheads.out && <span className={"note" + (deadheads.out.exception ? " exception" : "")} title={tr("出库回送")}>{deadheads.out.number} {clock(deadheads.out.leave, true)} ← {deadheads.depot}</span>}
        {deadheads && deadheads.back && <span className={"note" + (deadheads.back.exception ? " exception" : "")} title={tr("回库回送")}>{deadheads.back.number} → {deadheads.depot} {clock(deadheads.back.home, true)}</span>}
        {s.suspended && <span className="note suspended-note">{tr("停运")}</span>}
        {s.deployOrder && <span className="note">{tr("出车 ← {0}", orderDepotName(s.deployOrder))}</span>}
        {s.returnOrder ? <span className="note">{tr("回送 → {0}", orderDepotName(s.returnOrder))}</span>
          : s.link && s.link.fromStop < 0 && s.link.toStop === 0 ? <span className="note">{tr("接 {0}", clock(mod(s.link.toService - data.dayStartMillis, DAY)))}</span>
          : s.link ? <span className="note">{tr("直通")}</span> : s.returns ? <span className="note">{tr("回库")}</span> : null}
      </th>
    )
  }
  const isSelected = (s: any) => s.id === state.selected || s.route.id === state.selectedRoute || fn.inRange(s)
  const showDeadheadsBox = (
    <label className="hint"><input type="checkbox" checked={Boolean(v.showDeadheads)} onChange={e => {
      v.showDeadheads = e.target.checked
      try { localStorage.setItem("mtr-editor-show-deadheads", v.showDeadheads ? "1" : "0") } catch { /* private window */ }
      fn.renderTable()
      fn.renderAll()
    }} /> {tr("显示回送")}</label>
  )

  // ---------- Pointer and keys (old handlers on #view-table) ----------
  const onClick = (e: React.MouseEvent) => {
    const cell = (e.target as HTMLElement).closest("td[data-svc], th[data-svc]") as HTMLElement | null
    if (!cell) return
    const s = findService(cell.dataset.svc || "")
    if ((e.ctrlKey || e.metaKey) && cell.tagName === "TH" && s && fn.toggleChosen(s, e.shiftKey)) {
      v.tableMessage = state.range ? tr("选中 {0} 班，右键表头可以调整间隔", state.range.ids.length) : ""
      fn.renderAll()
      return
    }
    if (e.shiftKey && cell.tagName === "TH" && s && fn.chooseRange(s)) {
      v.tableMessage = tr("选中 {0} 班，右键表头可以调整间隔", state.range.ids.length)
      fn.renderAll()
      return
    }
    if (!e.shiftKey && !e.ctrlKey && !e.metaKey) state.range = null
    if (state.selected !== cell.dataset.svc || state.selectedRoute) {
      state.selected = cell.dataset.svc
      state.selectedRoute = null
      if (s) fn.centerOn(s)
      fn.renderAll()
    }
    startEditing(cell)
  }
  const onContextMenu = (e: React.MouseEvent) => {
    const cell = (e.target as HTMLElement).closest("td[data-svc], th[data-svc]") as HTMLElement | null
    if (!cell) return
    e.preventDefault()
    fn.cellMenu(e.nativeEvent, cell)
  }
  const onKeyDown = (e: React.KeyboardEvent) => {
    const cell = (e.target as HTMLElement).closest("td[data-svc]") as HTMLElement | null
    if (cell && !editing && (e.key === "Enter" || /^\d$/.test(e.key))) { e.preventDefault(); startEditing(cell) }
  }

  // ---------- A line's routes together ----------
  if (lineMode) {
    const wayOf = (route: any) => { for (let i = 1; i < route.rows.length; i++) if (route.rows[i] !== route.leaveRows[i - 1]) return route.rows[i] > route.leaveRows[i - 1] ? 0 : 1; return 0 }
    const ways = [0, 1].map(w => line.routes.filter((route: any) => wayOf(route) === w))
    const way = ways[state.lineWay] && ways[state.lineWay].length ? state.lineWay : ways[0].length ? 0 : 1
    const order: number[] = line.rows.map((_: unknown, i: number) => i)
    if (way === 1) order.reverse()
    const position = new Map(order.map((row, t) => [row, t]))
    const ends = (w: number) => [...new Set(ways[w].map((route: any) => shortName(route.stops[route.stops.length - 1].name)))].join(tr("、"))
    const services = ways[way].flatMap((route: any) => route.services).sort((a: any, b: any) => a.start - b.start)
    const tools = <>
      <div className="tt-tools"><b>{shortName(line.name)}</b><span className="hint">{tr("{0} · {1} 班 · 在左边选一个种别可以改表和添加班次", weekdayName(state.weekday), services.length)}</span>{showDeadheadsBox}</div>
      <div className="tt-tools"><span className="hint" style={{ flex: "none" }}>{tr("方向")}</span>
        {[0, 1].filter(w => ways[w].length).map(w => <button key={w} className={"table-chip" + (w === way ? " on" : "")} onClick={() => { state.lineWay = w; fn.renderAll() }}>{tr("往{0}", ends(w))}</button>)}</div>
    </>
    if (!services.length) return <Frame className={className} root={root} tools={tools}><div className="table-empty">{tr("这条线路{0}没有班次", weekdayName(state.weekday))}</div></Frame>
    const allTimes = services.map(timesOf)
    const visits = services.map((s: any) => {
      const route = s.route, at = new Map<string, number>(), seen = new Set<number>()
      route.rows.forEach((row: number, i: number) => {
        if (!at.has(row + "arr")) at.set(row + "arr", i)
        if (!at.has(route.leaveRows[i] + "dep")) at.set(route.leaveRows[i] + "dep", i)
        seen.add(position.get(row)!)
        seen.add(position.get(route.leaveRows[i])!)
      })
      const sections = new Set([...seen].map(t => line.rows[order[t]].section))
      return { at, seen, sections, first: Math.min(...seen), last: Math.max(...seen) }
    })
    const rows: ReactNode[] = []
    order.forEach((rowIndex, t) => {
      const row = line.rows[rowIndex]
      const arrives = visits.some((vis: any) => vis.at.has(rowIndex + "arr")), leaves = visits.some((vis: any) => vis.at.has(rowIndex + "dep"))
      const kinds = arrives && leaves ? ["arr", "dep"] : arrives ? ["arr"] : ["dep"]
      const sectionStart = t > 0 && line.rows[order[t - 1]].section !== row.section
      kinds.forEach((kind, k) => {
        rows.push(
          <tr key={rowIndex + kind} className={[k === 0 ? "first-of-station" : "", k === 0 && sectionStart ? "section-start" : ""].filter(Boolean).join(" ")}>
            {k === 0 && <th className={"st" + (row.repeat ? " repeat" : "")} rowSpan={kinds.length} title={row.name}>{row.repeat ? "↔ " : ""}{shortName(row.name)}</th>}
            <th className="kind">{kind === "dep" ? tr("发") : trc("arrival", "到")}</th>
            {services.map((s: any, j: number) => {
              const vis = visits[j], i = vis.at.get(rowIndex + kind), selected = isSelected(s)
              if (i !== undefined) return cellOf(s, i, kind, allTimes[j][i], selected)
              let mark = ""
              if (!vis.seen.has(t)) {
                if (!vis.sections.has(row.section)) mark = "‖"
                else if (t > vis.first && t < vis.last) mark = vis.seen.has(t - 1) ? "↳" : "‖"
              }
              if (mark === "↳" && k > 0) mark = ""
              return <td key={s.id} className={["pass", mark === "↳" ? "through" : "", selected ? "hl" : ""].filter(Boolean).join(" ")}>{mark}</td>
            })}
          </tr>,
        )
      })
    })
    return (
      <Frame className={className} root={root} tools={tools} onClick={onClick} onContextMenu={onContextMenu} onKeyDown={onKeyDown}>
        <table className="tt">
          <thead><tr><th className="st">{tr("车站")}</th><th className="kind" />{services.map((s: any) => headOf(s, isSelected(s), <span className="note" style={{ color: colorOf(s.route.color) }}>{shortName(s.route.name)}</span>))}</tr></thead>
          <tbody>{rows}</tbody>
        </table>
      </Frame>
    )
  }

  // ---------- One route's table ----------
  if (!r) return <Frame className={className} root={root}><div className="table-empty">{tr("在左边选一条线路的汇总时刻表")}</div></Frame>
  const services = r.services
  const timetable = fn.timetableOf(r), today = timetable.tables.findIndex((table: any) => (table.weekdays >> state.weekday) & 1)
  const managed = fn.isManaged(r), pending = fn.draftOf(r) && "managed" in fn.draftOf(r), drafted = Boolean(fn.draftOf(r) && fn.draftOf(r).timetable)
  const tableMenu = (e: React.MouseEvent, index: number) => {
    e.preventDefault()
    fn.openMenuAt(e.nativeEvent, [
      [tr("改名和运行的星期…"), () => fn.tableDialog(r, index)],
      [tr("清空这张表的班次"), timetable.tables[index].services.length ? () => fn.clearTable(r, index) : null],
      [timetable.tables.length > 1 ? tr("删除这张表") : tr("删除这张表（至少要留一张）"), timetable.tables.length > 1 ? () => setAsking({
        text: tr("删除“{0}”和它的 {1} 个班次？", tableName(timetable.tables[index], index), timetable.tables[index].services.length),
        go: () => fn.editTimetable(r, (edited: any) => { edited.tables.splice(index, 1) }),
      }) : null],
    ])
  }
  const perf = (key: string, value: string) => {
    const number = Number(value)
    if (!(number > 0)) return
    fn.editTimetable(r, (edited: any) => {
      const table = edited.tables[today]
      // Kept as the game keeps them: m/tick² (m/s² ÷ 400) and blocks per tick (km/h ÷ 72)
      if (key === "a") table.minAcceleration = number / 400
      else if (key === "d") table.minDeceleration = number / 400
      else table.minMaxSpeed = number / 72
    })
  }
  // The inputs start again from the shown route's and table's own value when either changes, or the value does: typed
  // into and left alone, one route's number isn't kept over another's (0.65.27)
  const perfKey = (key: string) => r.id + "/" + today + "/" + key + "/" + (today >= 0 ? fn.perfText(timetable.tables[today], key) : "")
  const tools = <>
    <div className="tt-tools"><b>{routeTitle(r)}</b>
      <span className="hint">{tr("{0} · {1} 班{2}{3}", weekdayName(state.weekday), services.length, r.hasTimes || r.pathFailure ? "" : tr(" · 正在向服务端取各站时刻"), drafted ? tr(" · 有没应用的草稿") : "")}</span>
      {r.pathFailure && <span className="err">{r.pathFailure}</span>}
      {fn.shortfallsOf(r).map((text: string) => <span key={text} className="err">{text}</span>)}
      <span className={"pill" + (managed ? " managed" : "")} title={tr("由高级编辑器管理的线路，游戏里的时刻表和停站只能查看")}>{managed ? tr("由高级编辑器管理") : tr("游戏内可编辑")}{pending ? tr("（草稿）") : ""}</span>
      <button onClick={() => fn.setManaged(r, !managed)}>{managed ? tr("交回游戏内编辑") : tr("改由高级编辑器管理")}</button>
      {fn.speedLimitOf(r) > 0 && <span className="pill" title={tr("列车在这条线路两站之间的轨道上不超过这个速度，也不超过轨道自己的限速")}>{tr("限速 {0} km/h", fn.speedLimitOf(r))}{fn.draftOf(r) && "speedLimit" in fn.draftOf(r) ? tr("（草稿）") : ""}</span>}
    </div>
    <div className="tt-tools"><span className="hint" style={{ flex: "none" }}>{tr("表")}</span>
      {timetable.tables.map((table: any, i: number) => (
        <button key={i} className={"table-chip" + (i === today ? " on" : "")} title={tr("切到这张表运行的第一天；右键改名、改星期或删除")}
          onClick={() => { const mask = table.weekdays; if (mask) fn.setWeekday(weekdaysShort().findIndex((_: unknown, d: number) => (mask >> d) & 1)) }}
          onContextMenu={e => tableMenu(e, i)}>{tableName(table, i)} · {daysText(table.weekdays)}</button>
      ))}
      <button className="table-chip" onClick={() => fn.tableDialog(r, -1)}>{tr("＋ 新建表")}</button>
      {today >= 0 && (
        <label className="hint" title={tr("运行时分按这组性能算，换车型时刻表不变；跑这条线的车比它慢时会提示")}>{tr("最低性能")} {tr("加速")}{" "}
          <input className="perf" key={perfKey("a")} defaultValue={fn.perfText(timetable.tables[today], "a")} inputMode="decimal" style={{ width: "3.5em" }} onBlur={e => perf("a", e.target.value)} /> m/s² {tr("减速")}{" "}
          <input className="perf" key={perfKey("d")} defaultValue={fn.perfText(timetable.tables[today], "d")} inputMode="decimal" style={{ width: "3.5em" }} onBlur={e => perf("d", e.target.value)} /> m/s² {tr("最高")}{" "}
          <input className="perf" key={perfKey("v")} defaultValue={fn.perfText(timetable.tables[today], "v")} inputMode="numeric" style={{ width: "3.5em" }} onBlur={e => perf("v", e.target.value)} /> km/h</label>
      )}
    </div>
    {today >= 0
      ? <AddServices r={r} today={today} showDeadheadsBox={showDeadheadsBox} ask={setAsking} />
      : <div className="tt-tools"><span className="err">{tr("{0}没有表，这一天不运行。{1}", weekdayName(state.weekday), v.tableMessage)}</span></div>}
    {asking && (
      <div className="tt-tools" role="alert"><span className="err">{asking.text}</span>
        <button onClick={() => { const go = asking.go; setAsking(null); go() }}>{tr("确定")}</button><button onClick={() => setAsking(null)}>{tr("取消")}</button></div>
    )}
  </>
  if (!services.length) return <Frame className={className} root={root} tools={tools}><div className="table-empty">{tr("这条线路{0}没有班次", weekdayName(state.weekday))}</div></Frame>
  const multipleTables = timetable.tables.length > 1
  const allTimes = services.map(timesOf)
  const rows: ReactNode[] = []
  r.stops.forEach((stop: any, i: number) => {
    const last = i === r.stops.length - 1
    const allPass = i > 0 && !last && services.every((s: any) => s.plans[i].passes)
    const kinds = i === 0 || last ? ["arr", "dep"] : allPass ? ["pass"] : ["arr", "dep"]
    kinds.forEach((kind, k) => {
      rows.push(
        <tr key={i + kind} className={k === 0 ? "first-of-station" : ""}>
          {k === 0 && (
            <th className="st" rowSpan={kinds.length} title={stop.name + fn.trackTitle(stop) + (stop.boundary ? tr("（时刻边界，可以在这里接出或接入）") : "")}>
              {shortName(stop.name)}
              {stop.boundary && <span className="boundary"> {tr("界")}</span>}
              {stop.tooShort && <span className="err" title={tr("车比站台长（站台 {0} 格）", stop.platformLength)}> ⚠</span>}
              {fn.isLoop(r) && i > 0 && i < r.stops.length - 1 && fn.loopEntryStop(r) === i && <span className="entry" title={tr("车厂的列车从这里接入环线")}> {tr("入")}</span>}
            </th>
          )}
          <th className="kind">{kind === "dep" ? tr("发") : kind === "pass" ? tr("通过") : trc("arrival", "到")}</th>
          {services.map((s: any, j: number) => cellOf(s, i, kind, allTimes[j][i], isSelected(s)))}
        </tr>,
      )
    })
  })
  return (
    <Frame className={className} root={root} tools={tools} onClick={onClick} onContextMenu={onContextMenu} onKeyDown={onKeyDown}>
      <table className="tt">
        <thead><tr><th className="st">{tr("车站")}</th><th className="kind" />{services.map((s: any) => headOf(s, isSelected(s), multipleTables ? <span className="note">{tableName(s.table, s.tableIndex)}</span> : null))}</tr></thead>
        <tbody>{rows}</tbody>
      </table>
    </Frame>
  )
}

/** The table in the old styles' box */
function Frame({ className, root, tools, children, ...events }: { className: string; root: React.RefObject<HTMLDivElement | null>; tools?: ReactNode; children: ReactNode } & React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={"legacy legacy-slot " + className} data-summary-table>
      <div ref={root} className="table-view" {...events}>
        {tools && <div className="summary-tools">{tools}</div>}
        <div className="summary-scroll scroll-thin">{children}</div>
      </div>
    </div>
  )
}

/**
 * Adding services to the day's table (old addServices): from a time, every interval to an end, or a pattern; services
 * already in the span are fitted to the new times, those left over removed after asking.
 */
function AddServices({ r, today, showDeadheadsBox, ask }: { r: any; today: number; showDeadheadsBox: ReactNode; ask: (a: { text: string; go: () => void } | null) => void }) {
  const { fn, v } = legacy
  const inputs = v.addInputs
  const [start, setStart] = useState<string>(inputs.start)
  const [end, setEnd] = useState<string>(inputs.end)
  const [count, setCount] = useState<string>(inputs.count)
  const add = () => {
    inputs.start = start; inputs.end = end; inputs.count = count
    try { localStorage.setItem("mtr-editor-add-services", JSON.stringify(inputs)) } catch { /* private window */ }
    const services = fn.servicesToAdd(start, end, count)
    if (!services) { v.tableMessage = v.PATTERN_HINT; fn.renderAll(); return }
    const startTime = parseClock(start.trim()), endTime = end.trim() ? parseClock(end.trim()) : null
    if (startTime !== null && endTime !== null && count.trim()) {
      const data = v.data
      const from = mod(startTime - data.offsetMillis, DAY), to = mod(endTime - data.offsetMillis, DAY)
      const fit = (globalThis as any).TimetableBatch.fitSpan(fn.timetableOf(r).tables[today].services, from, to, services)
      const apply = () => fn.editTimetable(r, (timetable: any) => {
        const table = timetable.tables[today]
        fit.removes.forEach((service: number) => fn.removeService(table, service))
        for (const [a, b] of fit.moves) { const error = fn.retimeService(table, a, b); if (error) return error }
        if (table.services.length + fit.adds.length > v.MAX_SERVICES) return tr("一张表最多 {0} 个班次", v.MAX_SERVICES)
        table.services.push(...fit.adds)
        return tr("{0}–{1}：保留 {2} 班，挪动 {3} 班，新增 {4} 班，删掉 {5} 班", start.trim(), end.trim(), fit.keep.length, fit.moves.length, fit.adds.length, fit.removes.length)
      })
      if (fit.removes.length) ask({ text: tr("{0}–{1} 已有的班次比新间隔多 {2} 班，多出的要删掉（连同接续和例外）。改动先放进草稿，确定吗？", start.trim(), end.trim(), fit.removes.length), go: apply })
      else apply()
      return
    }
    fn.editTimetable(r, (timetable: any) => {
      const table = timetable.tables[today]
      const added = services.filter((service: number) => !table.services.includes(service))
      if (table.services.length + added.length > v.MAX_SERVICES) return tr("一张表最多 {0} 个班次", v.MAX_SERVICES)
      table.services.push(...added)
      return added.length < services.length ? tr("已有的 {0} 班没有重复添加", services.length - added.length) : ""
    })
  }
  const enter = (e: React.KeyboardEvent) => { if (e.key === "Enter") { e.preventDefault(); e.stopPropagation(); add() } }
  return (
    <div className="tt-tools">
      <label>{tr("添加班次")}</label>
      <input placeholder={tr("起始 07:10")} title={tr("起始时刻；也可以写 07:10 + 10 * 00:03")} autoComplete="off" value={start} onChange={e => setStart(e.target.value)} onKeyDown={enter} aria-label={tr("添加班次")} />
      <input placeholder={tr("结束 09:10")} autoComplete="off" value={end} onChange={e => setEnd(e.target.value)} onKeyDown={enter} style={{ width: "7em" }} aria-label={tr("结束 09:10")} />
      <input placeholder={tr("间隔 5")} title={tr("期望间隔，分钟（也可以写 04:30 表示 4 分 30 秒）")} autoComplete="off" value={count} onChange={e => setCount(e.target.value)} onKeyDown={enter} style={{ width: "5em" }} aria-label={tr("间隔 5")} />
      <button onClick={add}>{tr("添加")}</button>
      {showDeadheadsBox}
      <button title={tr("删掉这张表的全部班次，连同直通、回库、出入库指令和例外；表本身、停站时分和运行的星期保留")} onClick={() => fn.clearTable(r, today)}>{tr("清空")}</button>
      <span className="err">{v.tableMessage}</span>
    </div>
  )
}
