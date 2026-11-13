/**
 * The folder tree of the media library (0.74.0), for audio and pictures alike: "all files", then each uploader with their
 * folders inside folders. Clicking a folder shows its files; files dragged from the list onto a folder move into it
 * (dragged onto the folder above, or the uploader, they move out). The folder chosen can be given a new folder inside,
 * renamed or deleted (what is in it moves up a level; no file is deleted). Before files go up, UploadTarget asks which of
 * the player's own folders they go to. The rules are in core/mediaFolders.ts; the library sends the actions.
 */
import { useState, type ReactElement } from "react"
import { tr } from "../i18n"
import { ConfirmButton } from "../ui/bits"
import { canDrop, childOf, depthOf, nameOf, nameProblem, parentOf, sameFolder, type FolderFile, type FolderNode, type FolderRef } from "../core/mediaFolders"

/** A folder action, as the libraries send it ({action: "folder", ...}) */
export type FolderAction = { op: "create" | "rename" | "delete" | "move"; owner: string; path: string; name?: string; folder?: string; keys?: string[] }

const problemText = (problem: string) => ({
  empty: tr("请输入文件夹名"),
  long: tr("文件夹名最多 32 个字"),
  character: tr("文件夹名不能有斜杠"),
  dots: tr("文件夹名不能只有点"),
  deep: tr("文件夹最多 8 层"),
  taken: tr("这里已经有同名文件夹"),
} as Record<string, string>)[problem] || ""

export function FolderTree(props: {
  roots: FolderNode[]
  current: FolderRef | null
  onSelect: (ref: FolderRef | null) => void
  canOrganise: (owner: string) => boolean
  /** The files being dragged from the list, or null */
  dragging: (FolderFile & { key: string })[] | null
  onAction: (action: FolderAction, label: string) => void
}) {
  const { roots, current, onSelect, canOrganise, dragging, onAction } = props
  const [over, setOver] = useState<FolderRef | null>(null)
  const [editing, setEditing] = useState<{ mode: "create" | "rename"; name: string; problem: string } | null>(null)

  const drop = (target: FolderRef) => {
    if (!dragging || !canDrop(dragging, target, canOrganise)) return
    onAction({ op: "move", owner: target.owner, path: "", folder: target.path, keys: dragging.map(file => file.key) }, target.path || tr("顶层"))
  }
  const row = (node: FolderNode, depth: number): ReactElement => {
    const ref = { owner: node.owner, path: node.path }
    const chosen = sameFolder(current, ref)
    const target = Boolean(dragging && canDrop(dragging, ref, canOrganise))
    return <li key={node.owner + "/" + node.path}>
      <button className={"flex w-full items-center gap-1 rounded-md px-1.5 py-1 text-left text-[13px] " + (chosen ? "tint-sel" : "hover:bg-panel2")}
        style={{ paddingLeft: 6 + depth * 14, outline: target && sameFolder(over, ref) ? "2px solid var(--accent)" : undefined }}
        data-folder={node.owner + ":" + node.path} aria-pressed={chosen}
        onClick={() => { setEditing(null); onSelect(ref) }}
        onDragOver={e => { if (!target) return; e.preventDefault(); e.dataTransfer.dropEffect = "move"; setOver(ref) }}
        onDragLeave={() => setOver(null)}
        onDrop={e => { if (!target) return; e.preventDefault(); setOver(null); drop(ref) }}>
        <span aria-hidden className="text-dim">{depth === 0 ? "◆" : "▸"}</span>
        <span className="min-w-0 flex-1 truncate">{node.name}</span>
        <span className="num text-[11px] text-dim">{node.total}</span>
      </button>
      {node.children.length > 0 && <ul className="m-0 list-none p-0">{node.children.map(child => row(child, depth + 1))}</ul>}
    </li>
  }

  const node = current && findIn(roots, current)
  const organise = Boolean(current && canOrganise(current.owner))
  const siblings = (path: string) => (findIn(roots, { owner: current!.owner, path }) || { children: [] as FolderNode[] }).children.map(child => child.name)
  const submit = () => {
    if (!editing || !current) return
    const parent = editing.mode === "create" ? current.path : parentOf(current.path)
    const others = siblings(parent).filter(name => editing.mode === "create" || name.toLowerCase() !== nameOf(current.path).toLowerCase())
    const problem = nameProblem(editing.name, others, depthOf(parent) + (editing.mode === "create" ? 0 : 0))
    if (problem) { setEditing({ ...editing, problem }); return }
    const name = editing.name.trim()
    onAction(editing.mode === "create" ? { op: "create", owner: current.owner, path: current.path, name } : { op: "rename", owner: current.owner, path: current.path, name }, name)
    setEditing(null)
    if (editing.mode === "rename") onSelect({ owner: current.owner, path: childOf(parentOf(current.path), name) })
  }

  return <nav className="flex min-h-0 flex-col gap-2" aria-label={tr("文件夹")} data-folder-tree>
    <ul className="scroll-thin m-0 min-h-0 flex-1 list-none overflow-auto p-0">
      <li><button className={"w-full rounded-md px-1.5 py-1 text-left text-[13px] " + (!current ? "tint-sel" : "hover:bg-panel2")} aria-pressed={!current} onClick={() => { setEditing(null); onSelect(null) }}>{tr("全部文件")}</button></li>
      {roots.map(root => row(root, 0))}
    </ul>
    {dragging && <p className="m-0 text-[12px] text-dim">{tr("拖到文件夹上移进去，拖到上一层或上传者上移出来")}</p>}
    {current && node && organise && (editing ? (
      <div className="flex flex-col gap-1 text-[12px]">
        <input className="h-7 rounded-md border bg-panel px-1.5" style={{ borderColor: editing.problem ? "var(--warn)" : "var(--control-line)" }} autoFocus value={editing.name} aria-label={editing.mode === "create" ? tr("新文件夹名") : tr("文件夹新名字")}
          onChange={e => setEditing({ ...editing, name: e.target.value, problem: "" })} onKeyDown={e => { if (e.key === "Enter") submit(); else if (e.key === "Escape") setEditing(null) }} />
        {editing.problem && <span className="text-warn">{problemText(editing.problem)}</span>}
        <div className="flex gap-1.5"><button className="btn btn-primary h-7" onClick={submit}>{tr("确定")}</button><button className="btn h-7" onClick={() => setEditing(null)}>{tr("取消")}</button></div>
      </div>
    ) : (
      <div className="flex flex-wrap gap-1.5">
        <button className="btn h-7" disabled={depthOf(current.path) >= 8} onClick={() => setEditing({ mode: "create", name: "", problem: "" })}>{tr("新建文件夹")}</button>
        {current.path && <button className="btn h-7" onClick={() => setEditing({ mode: "rename", name: nameOf(current.path), problem: "" })}>{tr("改名")}</button>}
        {current.path && <ConfirmButton confirm={tr("删除文件夹“{0}”？里面的文件和文件夹会移到上一层，不会删除文件。", nameOf(current.path))} onConfirm={() => { onAction({ op: "delete", owner: current.owner, path: current.path }, nameOf(current.path)); onSelect({ owner: current.owner, path: parentOf(current.path) }) }}>{tr("删除文件夹")}</ConfirmButton>}
      </div>
    ))}
  </nav>
}

const findIn = (roots: FolderNode[], ref: FolderRef): FolderNode | null => {
  const search = (node: FolderNode): FolderNode | null => (node.path.toLowerCase() === ref.path.toLowerCase() ? node : node.children.map(search).find(Boolean) || null)
  const root = roots.find(each => each.owner === ref.owner)
  return root ? search(root) : null
}

/**
 * Asks which of the player's own folders files go to before they go up (user 2026-11-13: confirm the folder on upload).
 */
export function UploadTarget(props: { files: File[]; targets: string[]; value: string; onChange: (path: string) => void; onConfirm: () => void; onCancel: () => void }) {
  const { files, targets, value, onChange, onConfirm, onCancel } = props
  return <div className="mx-3 mb-2 flex flex-wrap items-center gap-2 rounded-lg border border-brand p-2 text-[12px]" role="dialog" aria-label={tr("上传到")} data-upload-target>
    <span>{files.length === 1 ? tr("上传“{0}”到", files[0].name) : tr("上传 {0} 个文件到", files.length)}</span>
    <select className="h-7 rounded-md border border-ctl bg-panel px-1" value={targets.includes(value) ? value : ""} onChange={e => onChange(e.target.value)} aria-label={tr("文件夹")}>
      {targets.map(path => <option key={path} value={path}>{path ? path.split("/").join(" / ") : tr("顶层（不放进文件夹）")}</option>)}
    </select>
    <button className="btn btn-primary h-7" onClick={onConfirm}>{tr("上传")}</button>
    <button className="btn h-7" onClick={onCancel}>{tr("取消")}</button>
  </div>
}
