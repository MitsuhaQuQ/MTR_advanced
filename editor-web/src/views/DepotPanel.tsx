/**
 * A depot's settings in a side panel (the old code's depotDialog comes here; kept as oldDepotDialog): the routes it runs
 * by line, whether its trains may stay on the line, each vehicle pool's fleet, the sidings against the routes as one table
 * of ticks (none ticked: all the depot's routes) with how many trains each holds, the measured buffers to adopt, and the
 * network's least time to change ends and wait threshold. The table follows the routes ticked above at once (the old
 * dialog needed saving and opening again). Stored as the old dialog stores it (storeDepotEntry); the network settings
 * change only those two values and keep the rest of the drafted settings (the old dialog dropped logical lines and the
 * like from the draft).
 */
import { useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { SidePanel } from "../ui/bits"
import { tr } from "../i18n"

const field = "h-7 rounded-md border border-ctl bg-panel px-2 text-[13px]"
const H = ({ children }: { children: string }) => <h3 className="label-sm m-0 mb-1.5 mt-4 first:mt-0">{children}</h3>

type Siding = { fleet: string; routes: string[] }
type Buffer = { adopt: boolean; seconds: string }

export function DepotPanel({ depotId, onClose }: { depotId: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, draft = v.draft
  const depot = data ? fn.depotById(depotId) : null
  const drafted = depot ? (draft.depots || {})[depot.id] : null
  const current = drafted || (depot ? { routes: depot.routes.slice(), cycleRoutes: depot.cycleRoutes, sidings: {} } : null)
  const [routes, setRoutes] = useState<string[]>(() => (current ? current.routes.slice() : []))
  const [cycle, setCycle] = useState<boolean>(Boolean(current?.cycleRoutes))
  const [sidings, setSidings] = useState<Record<string, Siding>>(() => Object.fromEntries((depot?.sidings || []).map((s: any) => {
    const value = current.sidings[s.id] || { routes: (s.routes || []).slice(), fleet: s.fleet }
    return [s.id, { fleet: value.fleet < 0 ? "" : String(value.fleet), routes: value.routes.slice() }]
  })))
  const poolFleetOf = (pool: any) => (current?.poolFleets && current.poolFleets[pool.key] !== undefined ? current.poolFleets[pool.key] : pool.fleet)
  const [fleets, setFleets] = useState<Record<string, string>>(() => Object.fromEntries((depot?.pools || []).map((p: any) => { const f = poolFleetOf(p); return [p.key, f === null || f === undefined || f < 0 ? "" : String(f)] })))
  const addedOf = (b: any) => (current?.addedBuffers ? current.addedBuffers[b.key] : b.added)
  const [buffers, setBuffers] = useState<Record<string, Buffer>>(() => Object.fromEntries((depot?.buffers || []).map((b: any) => [b.key, { adopt: Boolean(addedOf(b)), seconds: String(addedOf(b) || b.seconds || "") }])))
  const [clearBuffers, setClearBuffers] = useState(Boolean(current?.clearBuffers))
  const [minReverse, setMinReverse] = useState(() => String(Math.round(fn.minReverseMillis() / 1000)))
  const [waitThreshold, setWaitThreshold] = useState(() => String(Math.round(fn.waitThresholdMillis() / 1000)))
  const [error, setError] = useState("")
  if (!depot) return null

  const vehicleOf = (pool: any) => (current.vehicleSettings && current.vehicleSettings[pool.key] !== undefined ? current.vehicleSettings[pool.key] : pool.vehicleSettings && pool.vehicleSettings.set ? pool.vehicleSettings : null)
  const serviceTypesOf = (pool: any) => (current.serviceTypes && current.serviceTypes[pool.vehicle] !== undefined ? current.serviceTypes[pool.vehicle] : pool.serviceTypes || "")
  const lines: any[] = data.lines.filter((line: any) => line.routes.length)
  const chosen: any[] = lines.flatMap(line => line.routes).filter(r => routes.includes(r.id))
  const bufferKinds: Record<string, string> = { out: tr("出库"), turn: tr("折返"), back: tr("回库") }
  const toggle = (list: string[], id: string) => (list.includes(id) ? list.filter(each => each !== id) : [...list, id])
  const number = (text: string) => text.trim() === "" || Number.isFinite(Number(text))

  const save = () => {
    if (Object.values(sidings).some(s => !number(s.fleet)) || Object.values(fleets).some(f => !number(f))) { setError(tr("车数写成数字，或者留空")); return }
    const reverseSeconds = Number(minReverse)
    if (minReverse.trim() === "" || !(reverseSeconds >= 0) || reverseSeconds > 600) { setError(tr("车数写成数字，或者留空")); return }
    const waitSeconds = Number(waitThreshold)
    if (!(waitSeconds >= 0) || waitSeconds > 3600) { setError(tr("等候分界写 0 到 3600 秒")); return }
    const addedBuffers: Record<string, number> = {}
    for (const [key, b] of Object.entries(buffers)) {
      if (!b.adopt) continue
      const seconds = Math.round(Number(b.seconds))
      if (!(seconds > 0) || seconds > 1200) { setError(tr("车数写成数字，或者留空；采用的余量写 1 到 1200 秒")); return }
      addedBuffers[key] = seconds
    }
    const sidingEntries: Record<string, { fleet: number; routes: string[] }> = {}
    depot.sidings.forEach((s: any) => {
      const value = sidings[s.id], text = value.fleet.trim()
      sidingEntries[s.id] = { fleet: text === "" ? -1 : Math.max(0, Math.min(999, Math.round(Number(text)))), routes: value.routes.filter(id => routes.includes(id)) }
    })
    const poolFleets: Record<string, number | null> = {}, vehicleSettings: Record<string, any> = {}
    ;(depot.pools || []).forEach((pool: any) => {
      const text = (fleets[pool.key] || "").trim()
      poolFleets[pool.key] = text === "" ? null : Math.max(0, Math.min(999, Math.round(Number(text))))
      vehicleSettings[pool.key] = vehicleOf(pool)
    })
    // The network's two values; whatever else the draft's network settings hold stays
    const settings: Record<string, any> = { ...(draft.networkSettings || {}) }
    delete settings.minReverseMillis
    delete settings.waitThresholdMillis
    const applied = data.networkSettings || {}
    if (Math.round(reverseSeconds) * 1000 !== applied.minReverseMillis) settings.minReverseMillis = Math.round(reverseSeconds) * 1000
    if (Math.round(waitSeconds) * 1000 !== applied.waitThresholdMillis) settings.waitThresholdMillis = Math.round(waitSeconds) * 1000
    draft.networkSettings = Object.keys(settings).length ? settings : null
    const appliedAdded: Record<string, number> = {}
    ;(depot.buffers || []).forEach((b: any) => { if (b.added) appliedAdded[b.key] = b.added })
    const sameAdded = JSON.stringify(Object.entries(addedBuffers).sort()) === JSON.stringify(Object.entries(appliedAdded).sort())
    const entry = {
      base: drafted ? drafted.base : depot.revision, routes, cycleRoutes: cycle, sidings: sidingEntries, poolFleets, vehicleSettings,
      serviceTypes: { ...(current.serviceTypes || {}) }, clearBuffers, addedBuffers: sameAdded ? null : addedBuffers,
    }
    fn.storeDepotEntry(depot, entry, sameAdded)
    onClose()
  }

  return (
    <SidePanel open onClose={onClose} title={tr("编辑车厂：{0}", fn.shortName(depot.name))} width={680}
      hint={tr("车厂负责的线路没有先后。哪列车跑哪班由运用决定；车按车型配属给车厂，同车型的存车线共用这些车，车回到哪条存车线都还是同一列。存车线可以只跑其中几条线路，最多停几列是它能停的车数。配属车数留空时按各存车线相加。")}
      footer={<>
        <button className="btn btn-primary" disabled={!data.canEdit} onClick={save}>{tr("放进草稿")}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
        {error && <span className="text-[12px] text-warn" role="alert">{error}</span>}
      </>}>
      <H>{tr("负责的线路")}</H>
      {lines.length ? lines.map(line => (
        <div key={line.key} className="mb-1.5 flex flex-wrap items-center gap-1.5">
          <span className="w-20 shrink-0 truncate text-[12px] text-dim">{fn.shortName(line.name)}</span>
          {line.routes.map((r: any) => {
            const on = routes.includes(r.id)
            return (
              <button key={r.id} type="button" aria-pressed={on} onClick={() => { setRoutes(toggle(routes, r.id)); setError("") }}
                className={"inline-flex h-6 items-center gap-1.5 rounded-pill border px-2 text-[12px] " + (on ? "tint-accent border-brand text-brand" : "border-ctl bg-panel")}>
                <span className="h-[3px] w-3 rounded-pill" style={{ background: fn.colorOf(r.color) }} />{fn.routeTitle(r)}
              </button>
            )
          })}
        </div>
      )) : <p className="text-[12px] text-dim">{tr("还没有线路")}</p>}
      <label className="mt-1 flex items-center gap-1.5 text-[13px]"><input type="checkbox" checked={cycle} onChange={e => setCycle(e.target.checked)} />
        {depot.byTimetable ? tr("允许长时间停在线上（两班之间不回库，运用跑完才回）") : tr("列车不回车厂，跑完最后一条线路后折返回第一条")}</label>

      {(depot.pools || []).length > 0 && <>
        <H>{tr("配属")}</H>
        {(depot.pools || []).map((pool: any) => (
          <div key={pool.key} className="mb-1.5 flex flex-wrap items-center gap-2 text-[13px]" data-pool={pool.key}>
            <b className="w-28 shrink-0 truncate">{pool.vehicle || pool.key}</b>
            <input className={field + " num w-20"} type="number" min={0} max={999} value={fleets[pool.key]} aria-label={tr("配属车数")}
              placeholder={pool.sum < 0 ? tr("存车线相加：按需要") : tr("存车线相加：{0}", pool.sum)} onChange={e => setFleets({ ...fleets, [pool.key]: e.target.value })} />
            <span className="min-w-0 flex-1 text-[12px] text-dim">{fn.vehicleSummary(pool, vehicleOf(pool), serviceTypesOf(pool))}</span>
          </div>
        ))}
      </>}

      <H>{tr("存车线")}</H>
      {!depot.sidings.length ? <p className="text-[12px] text-dim">{tr("没有存车线")}</p> : (
        <div className="scroll-thin overflow-x-auto">
          <table className="border-collapse text-[12px]">
            <thead>
              <tr>
                <th className="sticky left-0 bg-panel" />
                <th className="px-1 pb-1 text-left font-normal text-dim">{tr("最多停几列")}</th>
                {chosen.map(r => (
                  <th key={r.id} className="h-36 w-7 align-bottom font-normal" title={fn.routeTitle(r)}>
                    <span className="inline-block max-h-36 truncate whitespace-nowrap" style={{ writingMode: "vertical-rl" }}>
                      <span className="mb-1 inline-block h-2 w-2 rounded-full" style={{ background: fn.colorOf(r.color) }} /> {fn.shortName(r.name)}
                    </span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {depot.sidings.map((s: any) => {
                const value = sidings[s.id], mine = value.routes.filter(id => routes.includes(id))
                return (
                  <tr key={s.id} className="border-t border-grid" data-siding={s.id}>
                    <th scope="row" className="sticky left-0 max-w-[140px] truncate bg-panel py-1 pr-2 text-left font-normal">
                      {fn.shortName(s.name) || tr("存车线")}
                      {s.vehicle && <span className="ml-1 text-[11px] text-dim">{s.vehicle}</span>}
                      {!mine.length && chosen.length > 0 && <span className="ml-1 text-[11px] text-dim">{tr("全部")}</span>}
                    </th>
                    <td className="px-1"><input className={field + " num w-16"} type="number" min={0} max={999} placeholder={tr("不限")} value={value.fleet} aria-label={tr("{0} 最多停几列", fn.shortName(s.name))}
                      onChange={e => setSidings({ ...sidings, [s.id]: { ...value, fleet: e.target.value } })} /></td>
                    {chosen.map(r => (
                      <td key={r.id} className="text-center">
                        <input type="checkbox" checked={value.routes.includes(r.id)} aria-label={tr("{0} 跑 {1}", fn.shortName(s.name), fn.routeTitle(r))}
                          onChange={() => setSidings({ ...sidings, [s.id]: { ...value, routes: toggle(value.routes, r.id) } })} />
                      </td>
                    ))}
                  </tr>
                )
              })}
            </tbody>
          </table>
          <p className="mt-1 text-[12px] text-dim">{tr("一行都不勾的存车线跑车厂的全部线路。")}</p>
        </div>
      )}

      <H>{tr("实测余量")}</H>
      <p className="m-0 mb-1.5 text-[12px] text-dim">{tr("列车实际比计划多用的时间（最近 20 次的 85% 分位，多 15 秒以上才记），只作建议。勾上“采用”的才加在出入库和折返上排运用，时刻表不变；采用的变了，现有运用接得上就不自动重排，在车厂右键菜单里“重新排运用”。")}</p>
      {(depot.buffers || []).length ? <>
        <table className="w-full border-collapse text-[12px]">
          <tbody>
            {(depot.buffers || []).map((b: any) => {
              const value = buffers[b.key]
              return (
                <tr key={b.key} className="border-t border-grid" data-buffer={b.key}>
                  <td className="py-1 pr-2">{bufferKinds[b.kind] || b.kind}</td>
                  <td className="pr-2">{b.from} → {b.to}</td>
                  <td className="pr-2 text-dim">{b.seconds ? tr("实测 +{0} 秒", b.seconds) : tr("没有实测")}</td>
                  <td className="whitespace-nowrap">
                    <label className="mr-1 inline-flex items-center gap-1"><input type="checkbox" checked={value.adopt} onChange={e => setBuffers({ ...buffers, [b.key]: { ...value, adopt: e.target.checked } })} /> {tr("采用")}</label>
                    <input className={field + " num w-16"} type="number" min={1} max={1200} disabled={!value.adopt} value={value.seconds} onChange={e => setBuffers({ ...buffers, [b.key]: { ...value, seconds: e.target.value } })} /> {tr("秒")}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        <label className="mt-1 flex items-center gap-1.5 text-[13px]"><input type="checkbox" checked={clearBuffers} onChange={e => setClearBuffers(e.target.checked)} /> {tr("清空实测余量（采用的不变）")}</label>
      </> : <p className="m-0 text-[12px] text-dim">{tr("还没有记下")}</p>}

      <H>{tr("全网设置")}</H>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-0.5 text-[12px] text-dim">{tr("换端最短时间（全网，秒）")}
          <input className={field + " num"} type="number" min={0} max={600} value={minReverse} onChange={e => setMinReverse(e.target.value)} /></label>
        <label className="flex flex-col gap-0.5 text-[12px] text-dim">{tr("等候分界（全网，秒：两班之间等得比这长就离开站台去折返线）")}
          <input className={field + " num"} type="number" min={0} max={3600} value={waitThreshold} onChange={e => setWaitThreshold(e.target.value)} /></label>
      </div>
    </SidePanel>
  )
}
