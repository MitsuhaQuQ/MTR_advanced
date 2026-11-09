/**
 * The strip under the top bar (renderBanner in the old page): the connection, no permission to edit, routes changed in
 * the game since the draft, and what applying did.
 */
import { tr } from "../i18n"
import { useStore } from "../state/store"
import { resolveConflict } from "../core/draft"
import { routeTitle, shortName } from "../core/model"

export function Banner({ onReview }: { onReview: () => void }) {
  const store = useStore()
  const { model, draft, applyResult } = store
  const name = (id: string) => (model && model.routeById.get(id) ? routeTitle(model.routeById.get(id)!) : tr("已删除的线路"))
  const failed = Object.entries(draft.routes).filter(([, entry]) => entry.error || entry.conflict)
  const otherFailed = Object.values(draft.duties).some(entry => entry.error) || draft.created.some(made => made.error)

  const connectionText = {
    connecting: "", ok: "",
    key: tr("这个页面的链接已经失效。请在游戏的仪表盘里重新点“高级编辑器”。"),
    world: tr("游戏现在不在世界里，进入世界后会自动刷新。"),
    offline: tr("连不上游戏。游戏关了，或者离开了这个世界。请在游戏的仪表盘里重新点“高级编辑器”。"),
  }[store.connection]

  const conflictList = (entries: [string, any][]) => (
    <ul className="m-0 mt-1 flex flex-col gap-1 pl-5">
      {entries.map(([id, entry]) => (
        <li key={id}>
          <b>{name(id)}</b>：{entry.error || tr("游戏里的这条线路在草稿之后改过")}
          {entry.conflict && (
            <span className="ml-2 inline-flex flex-wrap gap-2">
              <button className="btn" onClick={() => store.edit(d => resolveConflict(model, d, id, true))}>{tr("以最新版本为基础，覆盖对方的修改")}</button>
              <button className="btn" onClick={() => store.edit(d => resolveConflict(model, d, id, false))}>{tr("放弃草稿，用游戏里的")}</button>
            </span>
          )}
        </li>
      ))}
    </ul>
  )

  let tone: "warn" | "ok" | "plain" = "plain", body: React.ReactNode = null
  if (connectionText) { tone = "warn"; body = connectionText }
  else if (model && !model.canEdit) { tone = "warn"; body = tr("没有编辑权限：需要创造模式，或服务端设置的权限等级。草稿照常保存，有权限后再应用。") }
  else if (!applyResult && failed.some(([, entry]) => entry.conflict)) {
    tone = "warn"
    body = <>{tr("下面的线路在草稿之后游戏里改过。它们的草稿保留着，应用时先不发，逐条处理后才能应用；其余线路照常应用：")}{conflictList(failed.filter(([, entry]) => entry.conflict))}</>
  } else if (applyResult && (!applyResult.ok || failed.length || otherFailed)) {
    tone = "warn"
    body = (
      <>
        {tr("应用了 {0} 项，下面的没有应用，留在草稿里：", applyResult.applied)}
        <ul className="m-0 mt-1 flex flex-col gap-1 pl-5">
          {applyResult.general.map((message, i) => <li key={"g" + i}>{message}</li>)}
          {Object.entries(draft.duties).filter(([, entry]) => entry.error).map(([id, entry]) => <li key={"d" + id}>{tr("车厂{0}的运用：{1}", shortName(model?.depotById(id)?.name ?? "?"), entry.error)}</li>)}
          {draft.created.filter(made => made.error).map((made, i) => <li key={"c" + i}>{tr("新种别 {0}：{1}", shortName(made.name), made.error)}</li>)}
        </ul>
        {failed.length > 0 && conflictList(failed)}
      </>
    )
  } else if (applyResult && applyResult.ok) { tone = "ok"; body = tr("已应用 {0} 项。", applyResult.applied) }
  if (!body) return null

  return (
    <div role="status" className={"flex shrink-0 items-start gap-3 border-b border-grid px-3 py-1.5 text-[13px] " + (tone === "warn" ? "tint-warn" : tone === "ok" ? "tint-accent" : "bg-panel2")}>
      <div className="min-w-0 flex-1">{body}</div>
      {applyResult && (
        <span className="flex shrink-0 gap-2">
          {tone === "warn" && <button className="btn" onClick={onReview}>{tr("看草稿")}</button>}
          <button className="btn btn-ghost" onClick={store.dismissResult} aria-label={tr("关闭")}>✕</button>
        </span>
      )}
    </div>
  )
}
