/**
 * Sync state (syncDialog in the old page): what doesn't match between the page and the game. Taking a train back and
 * the dispatcher's commands about contingency plans are sent at once, as in the old page.
 */
import { useState } from "react"
import { tr } from "../i18n"
import { useStore } from "../state/store"
import { syncProblems, type SyncProblem } from "../core/sync"
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
            ) : (
              // Ad hoc measures are set in the dispatcher's layer (step 6); until then in the old page
              <a key={i} className="btn" href="../#/timetable">{action.label}</a>
            ))}
          </div>
        ) : null}
      </div>
    </li>
  )
}
