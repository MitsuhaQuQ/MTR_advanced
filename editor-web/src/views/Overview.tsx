import type { ReactNode } from "react"
import { colorOf, shortName, type Depot, type Draft, type EditorData, type Route } from "../api/types"
import { tr } from "../i18n"
import type { LayerInfo } from "../shell/layers"

/**
 * Step 0 of the migration: each layer shows what the game has, read only, and points to the old page for editing until
 * the layer is moved here.
 */

function MovesIn({ layer }: { layer: LayerInfo }) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-grid bg-panel px-3 py-2 text-[13px]">
      <span className="text-dim">{tr("这一层在迁移第 {0} 步搬到新版，现在请在旧版里编辑。", layer.movesIn)}</span>
      <a className="btn ml-auto" href={layer.old}>{tr("在旧版里编辑")}</a>
    </div>
  )
}

export function NotYetHere({ layer }: { layer: LayerInfo }) {
  return <div className="flex max-w-3xl flex-col gap-4"><MovesIn layer={layer} /></div>
}

const Pill = ({ tone, children }: { tone: "warn" | "caution" | "sel" | "plain"; children: ReactNode }) => (
  <span className={"inline-flex h-5 shrink-0 items-center whitespace-nowrap rounded-pill px-2 text-[12px] font-medium " + {
    warn: "tint-warn text-warn", caution: "tint-caution text-caution", sel: "tint-sel text-sel", plain: "bg-panel2 text-dim",
  }[tone]}>{children}</span>
)

type LineGroup = { key: string; name: string; color: number; routes: Route[] }

/** The routes of each line group, as the old page groups them (setData in editor.js). */
export function linesOf(data: EditorData): LineGroup[] {
  const lines = new Map<string, LineGroup>()
  data.routes.forEach(r => {
    if (!lines.has(r.lineKey)) lines.set(r.lineKey, { key: r.lineKey, name: r.line, color: r.lineColor, routes: [] })
    lines.get(r.lineKey)!.routes.push(r)
  })
  return [...lines.values()].sort((a, b) => a.name.localeCompare(b.name))
}

const servicesOf = (r: Route) => r.tables.reduce((sum, t) => sum + t.services.length, 0)

export function LinesOverview({ data, draft, layer }: { data: EditorData; draft: Draft | null; layer: LayerInfo }) {
  const lines = linesOf(data)
  return (
    <div className="flex max-w-5xl flex-col gap-4">
      <MovesIn layer={layer} />
      {!lines.length && <p className="text-[13px] text-dim">{tr("没有线路")}</p>}
      {lines.map(line => (
        <section key={line.key} className="overflow-hidden rounded-lg border border-grid bg-panel">
          <h2 className="m-0 flex items-center gap-2 border-b border-grid bg-panel2 px-3 py-2 text-[14px] font-semibold">
            <span className="h-3 w-3 rounded-[2px]" style={{ background: colorOf(line.color) }} />
            {shortName(line.name) || tr("（未命名）")}
            <span className="num ml-auto text-[12px] font-normal text-dim">{tr("{0} 个种别", line.routes.length)}</span>
          </h2>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr className="text-left text-[12px] text-dim">
                  <th className="px-3 py-1.5 font-medium">{tr("种别")}</th>
                  <th className="px-3 py-1.5 font-medium">{tr("停站")}</th>
                  <th className="px-3 py-1.5 font-medium">{tr("时刻表")}</th>
                  <th className="px-3 py-1.5 text-right font-medium">{tr("班次")}</th>
                  <th className="px-3 py-1.5 font-medium">{tr("状态")}</th>
                </tr>
              </thead>
              <tbody>
                {line.routes.map(r => (
                  <tr key={r.id} className="border-t border-grid">
                    <td className="px-3 py-1.5">
                      <span className="flex items-center gap-2"><span className="h-1 w-4 shrink-0 rounded-sm" style={{ background: colorOf(r.color) }} />{shortName(r.name) || tr("（未命名）")}</span>
                    </td>
                    <td className="max-w-[320px] truncate px-3 py-1.5 text-dim" title={r.stops.map(s => shortName(s.name)).join(" → ")}>
                      <span className="num text-ink">{r.stops.length}</span> · {r.stops.length ? `${shortName(r.stops[0].name)} → ${shortName(r.stops[r.stops.length - 1].name)}` : "—"}
                    </td>
                    <td className="num px-3 py-1.5">{r.timetable ? r.tables.length : <span className="font-ui text-dim">{tr("不按时刻表")}</span>}</td>
                    <td className="num px-3 py-1.5 text-right">{servicesOf(r)}</td>
                    <td className="px-3 py-1.5">
                      <span className="flex flex-wrap gap-1">
                        {draft?.routes[r.id] && <Pill tone="sel">{tr("有草稿")}</Pill>}
                        {r.pathFailure && <Pill tone="warn">{tr("寻路失败")}</Pill>}
                        {r.circular && <Pill tone="plain">{tr("环线")}</Pill>}
                        {r.connectingLine && <Pill tone="caution">{tr("联络线")}</Pill>}
                        {r.editors && <Pill tone="caution">{tr("{0} 正在编辑", r.editors)}</Pill>}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </div>
  )
}

const pathText = (depot: Depot) => {
  switch (depot.path?.state) {
    case "generating": return { tone: "plain" as const, text: tr("正在寻路") }
    case "failed": return { tone: "warn" as const, text: tr("寻路失败") }
    case "stale": return { tone: "caution" as const, text: tr("要重新寻路") }
    default: return null
  }
}

export function DepotsOverview({ data, draft, layer }: { data: EditorData; draft: Draft | null; layer: LayerInfo }) {
  return (
    <div className="flex max-w-5xl flex-col gap-4">
      <MovesIn layer={layer} />
      {!data.depots.length && <p className="text-[13px] text-dim">{tr("没有车厂")}</p>}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {data.depots.map(depot => {
          const path = pathText(depot)
          const routes = depot.routes.map(id => data.routes.find(r => r.id === id)).filter((r): r is Route => !!r)
          return (
            <section key={depot.id} className="flex flex-col gap-2 rounded-lg border border-grid bg-panel p-3">
              <h2 className="m-0 flex items-center gap-2 text-[14px] font-semibold">
                <span className="min-w-0 truncate">{shortName(depot.name) || tr("（未命名）")}</span>
                <span className="ml-auto flex gap-1">
                  {draft?.duties?.[depot.id] !== undefined && <Pill tone="sel">{tr("运用有草稿")}</Pill>}
                  {depot.notConfigured && <Pill tone="caution">{tr("没设置")}</Pill>}
                  {path && <Pill tone={path.tone}>{path.text}</Pill>}
                </span>
              </h2>
              <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[13px]">
                <dt className="text-dim">{tr("存车线")}</dt><dd className="num m-0">{depot.sidings.length}</dd>
                <dt className="text-dim">{tr("车型")}</dt><dd className="m-0 min-w-0 truncate">{(depot.pools || []).map(p => p.vehicle || p.key).join("、") || "—"}</dd>
                <dt className="text-dim">{tr("线路")}</dt>
                <dd className="m-0 flex min-w-0 flex-wrap gap-1">
                  {routes.length ? routes.map(r => (
                    <span key={r.id} className="flex items-center gap-1 text-[12px]"><span className="h-1 w-3 rounded-sm" style={{ background: colorOf(r.color) }} />{shortName(r.name)}</span>
                  )) : <span className="text-dim">—</span>}
                </dd>
              </dl>
              {depot.path?.state === "failed" && depot.path.text && <p className="m-0 text-[12px] text-warn">{depot.path.text}</p>}
            </section>
          )
        })}
      </div>
    </div>
  )
}
