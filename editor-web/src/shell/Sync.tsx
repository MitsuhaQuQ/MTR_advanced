/**
 * Sync state (syncDialog in the old page): what doesn't match between the page and the game. Taking a train back,
 * the dispatcher's commands about contingency plans and an ad hoc measure for one train are sent at once, not drafted.
 */
import { useState } from "react"
import { tr } from "../i18n"
import { useStore } from "../state/store"
import { plansByAnchor, syncProblems, type SyncProblem } from "../core/sync"
import { stopLabel, turnPairsOf } from "../core/depots"
import { shortName } from "../core/model"
import { ConfirmButton, SidePanel } from "../ui/bits"

const formatDuration = (millis: number) => (millis >= 60000 ? tr("{0} 分 {1} 秒", Math.floor(millis / 60000), Math.round((millis % 60000) / 1000)) : tr("{0} 秒", Math.round(millis / 1000)))

export const useSyncProblems = () => {
  const store = useStore()
  return syncProblems(store.connection, store.model, store.draft)
}

export function SyncPanel({ open, onClose, onOpenRoute }: { open: boolean; onClose: () => void; onOpenRoute?: (routeId: string) => void }) {
  const store = useStore()
  const problems = useSyncProblems()
  const model = store.model
  const turns = model ? model.depots.flatMap((depot: any) => turnPairsOf(model, depot).filter(pair => pair.millis !== null && pair.millis !== undefined)
    .map(pair => tr("车厂{0}：{1} → {2} 实际要 {3}", shortName(depot.name) || tr("（未命名车厂）"), stopLabel(pair.from), stopLabel(pair.to), formatDuration(pair.millis as number)))) : []
  return (
    <SidePanel open={open} onClose={onClose} title={tr("同步状态")} width={520}
      hint={store.connection === "ok" ? tr("已同步 {0}，每 1.5 秒检查一次游戏里的变化。", new Date(store.loadedAt || Date.now()).toLocaleTimeString()) : tr("没有连上游戏。")}>
      {problems.length ? (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {problems.map((p, i) => <Problem key={i} problem={p} onClose={onClose} onOpenRoute={onOpenRoute} />)}
        </ul>
      ) : <p className="m-0 text-[13px]">{tr("页面和游戏一致，没有发现问题。")}</p>}
      {turns.length > 0 && (
        <>
          <h3 className="mb-1 mt-4 text-[13px] font-semibold">{tr("同站换站台用时（轨道上实测，含 30 秒余量）")}</h3>
          <ul className="m-0 flex flex-col gap-0.5 pl-5 text-[12px] text-dim">{turns.slice(0, 40).map((text, i) => <li key={i}>{text}</li>)}</ul>
        </>
      )}
    </SidePanel>
  )
}

const LEVEL = { err: "border-warn", warn: "border-caution", hint: "border-grid" }
const DOT = { err: "bg-warn", warn: "bg-caution", hint: "bg-gridstrong" }

function Problem({ problem: p, onClose, onOpenRoute }: { problem: SyncProblem; onClose: () => void; onOpenRoute?: (routeId: string) => void }) {
  const store = useStore()
  const [sent, setSent] = useState<Record<number, "sending" | "sent">>({})
  const [adhoc, setAdhoc] = useState<number | null>(null)
  const canEdit = Boolean(store.model?.canEdit)
  const send = async (index: number, operations: any[]) => {
    setSent(s => ({ ...s, [index]: "sending" }))
    const ok = await store.sendNow(operations)
    setSent(s => { const next = { ...s }; if (ok) next[index] = "sent"; else delete next[index]; return next })
  }
  return (
    <li className={"flex gap-2 rounded-md border-l-[3px] bg-panel2 px-3 py-2 text-[13px] " + LEVEL[p.level]}>
      <span className={"mt-1.5 h-2 w-2 shrink-0 rounded-full " + DOT[p.level]} aria-label={p.level === "err" ? tr("错误") : p.level === "warn" ? tr("提醒") : tr("说明")} />
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        {p.route && onOpenRoute
          ? <button className="m-0 cursor-pointer border-0 bg-transparent p-0 text-left text-sel underline-offset-2 hover:underline" onClick={() => { onClose(); onOpenRoute(p.route!) }}>{p.text}</button>
          : <span>{p.text}</span>}
        {canEdit && (p.recall || p.actions?.length) ? (
          <div className="flex flex-wrap gap-2">
            {p.recall && (sent[-1] === "sent" ? <span className="text-dim">{tr("已收回")}</span> : (
              <ConfirmButton confirm={tr("把这列车直接收回存车线？它不走轨道，从站台上直接回到存车线。")} disabled={sent[-1] === "sending"}
                onConfirm={() => send(-1, [{ type: "recall", ...p.recall }])}>{tr("直接收回")}</ConfirmButton>
            ))}
            {(p.actions || []).map((action, i) => action.op ? (
              sent[i] === "sent" ? <span key={i} className="text-dim">{tr("已发送")}</span>
                : <button key={i} className="btn" disabled={sent[i] === "sending"} onClick={() => send(i, [action.op])}>{action.label}</button>
            ) : action.adhoc ? (
              sent[i] === "sent" ? <span key={i} className="text-dim">{tr("已发送")}</span>
                : <button key={i} className="btn" aria-expanded={adhoc === i} onClick={() => setAdhoc(adhoc === i ? null : i)}>{action.label}</button>
            ) : null)}
          </div>
        ) : null}
        {canEdit && adhoc !== null && p.actions?.[adhoc]?.adhoc && sent[adhoc] !== "sent" && (
          <AdhocForm target={p.actions[adhoc].adhoc!} sending={sent[adhoc] === "sending"}
            onSend={async setting => { const i = adhoc; await send(i, [{ type: "contingency", system: p.actions![i].adhoc!.system, action: "adhoc", train: p.actions![i].adhoc!.train, setting }]) }}
            onCancel={() => setAdhoc(null)} />
        )}
      </div>
    </li>
  )
}

type Measure = { turn: string; turnStation: string; afterTurn: string; skipStations: string[]; takeover: boolean }

/**
 * What is done with one train there and then, by a dispatcher, until ended (D2; adhocDialog in the old page): turn back
 * early (at the nearest station that can, or a chosen one; then carry on in service or run empty to the depot), pass
 * stations without stopping, a spare train taking over the rest of its duty. Sent to the game at once; ended with
 * "解除" here.
 */
function AdhocForm({ target, sending, onSend, onCancel }: { target: { system: string; train: string }; sending: boolean; onSend: (setting: Measure) => void; onCancel: () => void }) {
  const store = useStore()
  const owner = store.model ? plansByAnchor(store.model, target.system) : null
  const stations: any[] = owner?.system?.stations || []
  const [m, setM] = useState<Measure>(() => {
    const current = (owner?.adhoc || {})[target.train]
    return current ? { ...current, skipStations: [...(current.skipStations || [])] } : { turn: "nearest", turnStation: "0", afterTurn: "resume", skipStations: [], takeover: false }
  })
  const [error, setError] = useState("")
  const change = (patch: Partial<Measure>) => { setM({ ...m, ...patch }); setError("") }
  const go = () => {
    if (m.turn === "station" && m.turnStation === "0") return setError(tr("选了指定车站折返，但没选车站"))
    if (m.turn === "none" && !m.skipStations.length && !m.takeover) return setError(tr("什么处置都没选"))
    onSend({ ...m, afterTurn: m.afterTurn === "deadhead" ? "deadhead" : "resume" })
  }
  const field = "h-7 rounded-md border border-ctl bg-panel px-2 text-[13px]", small = "flex items-center gap-1.5 text-[13px]"
  return (
    <div className="flex flex-col gap-2 rounded-md border border-grid bg-panel p-2" data-adhoc={target.train}>
      <b className="text-[13px]">{tr("临时处置：列车 {0}", target.train)}</b>
      <label className={small}>{tr("临时折返")}
        <select className={field} value={m.turn} onChange={e => change({ turn: e.target.value })}>
          <option value="none">{tr("不折返")}</option><option value="nearest">{tr("最近的能折返的站")}</option><option value="station">{tr("指定车站")}</option>
        </select></label>
      {m.turn === "station" && <label className={small}>{tr("折返站")}
        <select className={field} value={m.turnStation} onChange={e => change({ turnStation: e.target.value })}>
          <option value="0">—</option>{stations.map(s => <option key={s.id} value={s.id}>{shortName(s.name)}</option>)}
        </select></label>}
      {m.turn !== "none" && <label className={small}>{tr("折返以后")}
        <select className={field} value={m.afterTurn === "deadhead" ? "deadhead" : "resume"} onChange={e => change({ afterTurn: e.target.value })}>
          <option value="resume">{tr("接线运营（空车折返后重新载客）")}</option><option value="deadhead">{tr("回送回库")}</option>
        </select></label>}
      {m.turn !== "none" && <p className="m-0 text-[12px] text-dim">{tr("折返前都先在折返站清客。")}</p>}
      <div className="label-sm">{tr("跳过这些站（通过不停）：")}</div>
      <div className="flex flex-wrap gap-x-3 gap-y-1">
        {stations.length ? stations.map(s => (
          <label key={s.id} className={small}><input type="checkbox" checked={m.skipStations.includes(s.id)}
            onChange={e => change({ skipStations: e.target.checked ? [...m.skipStations, s.id] : m.skipStations.filter(id => id !== s.id) })} /> {shortName(s.name)}</label>
        )) : <span className="text-[12px] text-dim">{tr("路网的线路还没有车站")}</span>}
      </div>
      <label className={small}><input type="checkbox" checked={m.takeover} onChange={e => change({ takeover: e.target.checked })} /> {tr("由备车接替余下的运用")}</label>
      <p className="m-0 text-[12px] text-dim">{tr("马上发给游戏，不进草稿；在“同步状态”里解除。")}</p>
      {error && <p className="m-0 text-[12px] text-warn" role="alert">{error}</p>}
      <div className="flex gap-2">
        <button className="btn btn-primary" disabled={sending} onClick={go}>{tr("发送")}</button>
        <button className="btn" onClick={onCancel}>{tr("取消")}</button>
      </div>
    </div>
  )
}
