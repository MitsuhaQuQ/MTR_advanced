/**
 * The draft's changes before applying (draftChangesDialog in the old page): part by part against the game's version,
 * each with a way to drop it, conflicts with their two ways out, undo, clearing all, and Apply.
 */
import { tr } from "../i18n"
import { useStore } from "../state/store"
import { changeSections, conflictCount, draftCount, drop, emptyDraft, resolveConflict, type ChangeSection } from "../core/draft"
import { ConfirmButton, Kbd, SidePanel } from "../ui/bits"

export function ReviewPanel({ open, onClose, onOpenRoute }: { open: boolean; onClose: () => void; onOpenRoute?: (routeId: string) => void }) {
  const store = useStore()
  const { model, draft } = store
  const count = draftCount(draft), conflicts = conflictCount(draft)
  const sections = model ? changeSections(model, draft, store.boundaryErrors) : []
  const canApply = !store.applying && count > conflicts && Boolean(model?.canEdit)
  return (
    <SidePanel open={open} onClose={onClose} title={tr("草稿的改动")} hint={tr("和游戏里现在的版本比。按“应用”才发给游戏。")}
      footer={<>
        <button className="btn" disabled={!store.canUndo} onClick={store.undo} title={tr("撤销（Ctrl+Z）")}>{tr("撤销")}</button>
        <button className="btn" disabled={!store.canRedo} onClick={store.redo} title={tr("重做（Ctrl+Y）")}>{tr("重做")}</button>
        <ConfirmButton className="btn" disabled={!count} confirm={tr("清空全部草稿？草稿里的 {0} 项改动都丢掉；游戏里的不受影响。", count)} onConfirm={() => store.edit(() => emptyDraft())}>
          {tr("清空全部草稿…")}
        </ConfirmButton>
        <span className="flex-1" />
        <button className="btn btn-primary" disabled={!canApply} onClick={async () => { await store.apply() }}
          title={!model?.canEdit ? tr("没有编辑权限") : conflicts ? tr("有 {0} 条线路在游戏里被改过，应用时先不发，处理后再应用", conflicts) : tr("把草稿发给游戏")}>
          {store.applying ? tr("正在应用…") : tr("应用")}
        </button>
      </>}>
      {!count && <p className="m-0 text-[13px] text-dim">{tr("没有草稿")}</p>}
      <div className="flex flex-col gap-3">
        {sections.map(section => <Section key={section.key} section={section} onClose={onClose} onOpenRoute={onOpenRoute} />)}
      </div>
      {count > 0 && <p className="mt-4 text-[12px] text-dim"><Kbd>Ctrl</Kbd> <Kbd>Z</Kbd> {tr("撤销")} · <Kbd>Ctrl</Kbd> <Kbd>Y</Kbd> {tr("重做")}</p>}
    </SidePanel>
  )
}

function Section({ section, onClose, onOpenRoute }: { section: ChangeSection; onClose: () => void; onOpenRoute?: (routeId: string) => void }) {
  const store = useStore()
  return (
    <section className={"rounded-lg border bg-panel p-3 " + (section.conflict || section.error ? "border-warn" : "border-grid")}>
      <h3 className="m-0 text-[14px] font-semibold">{section.title}</h3>
      {section.error && <p role="alert" className="mb-0 mt-1 text-[12px] text-warn">{section.error}</p>}
      {section.conflict && (
        <div className="mt-2 flex flex-wrap gap-2">
          <button className="btn" onClick={() => store.edit(d => resolveConflict(store.model, d, section.route!, true))}>{tr("以最新版本为基础，覆盖对方的修改")}</button>
          <button className="btn" onClick={() => store.edit(d => resolveConflict(store.model, d, section.route!, false))}>{tr("放弃草稿，用游戏里的")}</button>
        </div>
      )}
      {section.items.length > 0 && (
        <ul className="mb-0 mt-2 flex list-disc flex-col gap-1 pl-5 text-[13px]">
          {section.items.map((item, i) => typeof item === "string" ? <li key={i}>{item}</li> : (
            <li key={i}>
              <b>{item.title}</b>
              <ul className="mt-1 flex list-[circle] flex-col gap-0.5 pl-5 text-dim">{item.lines.map((line, k) => <li key={k} className="text-ink">{line}</li>)}</ul>
            </li>
          ))}
        </ul>
      )}
      {(section.drop || (section.route && onOpenRoute)) && (
        <div className="mt-2 flex flex-wrap gap-2">
          {section.route && onOpenRoute && <button className="btn btn-ghost" onClick={() => { onClose(); onOpenRoute(section.route!) }}>{tr("跳到这里")}</button>}
          {section.drop && <button className="btn btn-ghost" onClick={() => store.edit(d => drop(d, section.drop!))}>{section.drop.kind === "route" ? tr("放弃这条的草稿") : tr("放弃这项草稿")}</button>}
        </div>
      )}
    </section>
  )
}
