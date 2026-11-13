import { useEffect, useMemo, useRef, useState } from "react"
import { key } from "../api/client"
import { tr } from "../i18n"
import { ConfirmButton } from "../ui/bits"
import { FolderTree, UploadTarget, type FolderAction } from "./MediaFolders"
import { buildTree, DRAG_TYPE, filesIn, findNode, uploadTargets, type FolderRef, type KeptFolder } from "../core/mediaFolders"

type Entry = { hash: string; name: string; width: number; height: number; size: number; canDelete: boolean; uploader?: string; uploaderName: string; folder?: string; uploadedAt: number }
/** folders, self and canManage: folders of pictures (0.74.0) */
type Library = { entries: Entry[]; canUpload: boolean; canManage?: boolean; busy: boolean; message: string; maxBytes: number; maxDimension: number; maxTotalBytes?: number; totalBytes?: number; folders?: KeptFolder[]; self?: string }
const headers = { "X-MTR-Editor-Key": key }
const endpoint = (path: string) => new URL("/api/" + path, location.origin).toString()
const base64 = (blob: Blob) => new Promise<string>((resolve, reject) => {
  const reader = new FileReader()
  reader.onload = () => resolve(String(reader.result).split(",")[1])
  reader.onerror = reject
  reader.readAsDataURL(blob)
})

/** What a file is by its first bytes: "png", "jpeg" or "" */
const kindOf = async (file: Blob) => {
  const head = new Uint8Array(await file.slice(0, 8).arrayBuffer())
  if (head.length === 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((byte, i) => head[i] === byte)) return "png"
  if (head.length >= 3 && head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff) return "jpeg"
  return ""
}

/**
 * The game stores PNG, JPEG (0.71.0) and GIF of at most maxBytes. A PNG or JPEG that fits goes as it is; one too big is
 * saved again in its own kind (JPEG at quality 0.9) and, if still too big, scaled down until it fits. Before, every image
 * was saved as PNG, often several times a photo's JPEG size, so a 3.6 MB photo was refused as "over 5 MB" (Q world,
 * 2026-11-12). @return scaled: the size it was scaled to, if it was
 */
export async function prepareImage(file: File, maxDimension: number, maxBytes: number): Promise<{ name: string; blob: Blob; scaled?: [number, number] }> {
  if (!/\.(png|jpe?g|gif)$/i.test(file.name) || file.size > 16 * 1024 * 1024) throw new Error(tr("请选择不超过 16 MB 的 PNG、JPEG 或 GIF 图片。"))
  const baseName = file.name.replace(/\.(png|jpe?g|gif)$/i, "")
  if (/\.gif$/i.test(file.name)) {
    if (file.size > maxBytes) throw new Error(tr("GIF 不能超过 5 MB。"))
    const header = new Uint8Array(await file.slice(0, 10).arrayBuffer())
    if (header.length < 10 || !/^GIF8[79]a$/.test(String.fromCharCode(...header.slice(0, 6)))) throw new Error(tr("无法读取图片。"))
    const width = header[6] | (header[7] << 8)
    const height = header[8] | (header[9] << 8)
    if (!width || !height || width > maxDimension || height > maxDimension) throw new Error(tr("图片宽高不能超过 {0} 像素。", maxDimension))
    return { name: baseName + ".gif", blob: file }
  }
  const bitmap = await createImageBitmap(file)
  try {
    if (bitmap.width > maxDimension || bitmap.height > maxDimension) throw new Error(tr("图片宽高不能超过 {0} 像素。", maxDimension))
    const kind = await kindOf(file)
    const extension = kind === "jpeg" ? ".jpg" : ".png"
    if (kind && file.size <= maxBytes) return { name: baseName + extension, blob: file }
    const type = kind === "jpeg" ? "image/jpeg" : "image/png"
    let scale = 1
    for (let attempt = 0; attempt < 8; attempt++) {
      const width = Math.max(1, Math.round(bitmap.width * scale)), height = Math.max(1, Math.round(bitmap.height * scale))
      const canvas = document.createElement("canvas")
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext("2d")
      if (!context) throw new Error(tr("无法读取图片。"))
      context.imageSmoothingQuality = "high"
      context.drawImage(bitmap, 0, 0, width, height)
      const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error(tr("无法读取图片。"))), type, 0.9))
      if (blob.size <= maxBytes) return scale === 1 ? { name: baseName + extension, blob } : { name: baseName + extension, blob, scaled: [width, height] }
      // The size goes roughly with the pixels; a little under, so most images fit the next time
      scale *= Math.min(0.9, Math.sqrt(maxBytes / blob.size) * 0.95)
    }
    throw new Error(tr("转换后的图片不能超过 5 MB。"))
  } finally { bitmap.close() }
}

export function ImagesLibrary() {
  const [data, setData] = useState<Library | null>(null)
  const [search, setSearch] = useState("")
  const [uploader, setUploader] = useState("")
  const [selected, setSelected] = useState<string | null>(null)
  const [preview, setPreview] = useState("")
  const [message, setMessage] = useState("")
  const [sending, setSending] = useState(false)
  // Folders (0.74.0): the one open (null: every picture), the pictures ticked, the ones dragged, pictures waiting for a folder
  const [current, setCurrent] = useState<FolderRef | null>(null)
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [dragging, setDragging] = useState<(Entry & { key: string })[] | null>(null)
  const [pending, setPending] = useState<File[] | null>(null)
  const [uploadFolder, setUploadFolder] = useState("")
  const input = useRef<HTMLInputElement>(null)
  const busy = useRef(false)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    let timer = 0
    const poll = async () => {
      try {
        const response = await fetch(endpoint("images"), { headers, cache: "no-store" })
        if (!response.ok) throw new Error()
        const next: Library = await response.json()
        if (alive.current) setData(next)
      } catch { if (alive.current) setMessage(tr("连不上游戏，游戏可能已经关闭。")) }
      if (alive.current) timer = window.setTimeout(poll, 2000)
    }
    poll()
    return () => { alive.current = false; clearTimeout(timer) }
  }, [])
  const entry = data?.entries.find(item => item.name === selected)
  const tree = useMemo(() => (data ? buildTree(data.entries, data.folders || [], data.self || "") : []), [data])
  useEffect(() => { if (data && current && !findNode(tree, current)) setCurrent(null) }, [tree])
  const self = data?.self || ""
  const canOrganise = (owner: string) => Boolean(data && owner && (owner === self || data.canManage))
  const shown = (data ? filesIn(data.entries, current, Boolean(search.trim())) : []).filter(item => (!uploader || item.uploaderName === uploader) && item.name.toLowerCase().includes(search.toLowerCase()))
  const askUpload = (files: File[]) => {
    if (!data?.canUpload || !files.length) return
    setUploadFolder(current && current.owner === self ? current.path : "")
    setPending(files)
  }
  const folderAction = (action: FolderAction) => { setChecked(new Set()); setMessage(""); post({ action: "folder", ...action }).catch(error => setMessage(error.message)) }
  useEffect(() => {
    let stopped = false, timer = 0
    setPreview("")
    const load = async () => {
      if (!entry) return
      try {
        const response = await fetch(endpoint("image-file?hash=" + encodeURIComponent(entry.hash)), { headers, cache: "no-store" })
        if (response.ok) {
          const blob = await response.blob()
          const encoded = await base64(blob)
          const mime = /^image\/(gif|jpeg|png)$/.test(blob.type) ? blob.type : "image/png"
          if (!stopped) setPreview("data:" + mime + ";base64," + encoded)
          return
        }
      } catch { /* A download in progress is retried without leaving the library. */ }
      if (!stopped) timer = window.setTimeout(load, 1000)
    }
    load()
    return () => { stopped = true; clearTimeout(timer) }
  }, [entry?.hash])
  const post = async (body: unknown) => {
    const response = await fetch(endpoint("images"), { method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: JSON.stringify(body) })
    if (!response.ok) throw new Error(tr("发送失败，游戏可能已经关闭"))
    const result = await response.json()
    if (!result.ok) throw new Error(result.message)
  }
  const upload = async (files: File[], folder: string) => {
    if (!data?.canUpload || data.busy || busy.current) return
    busy.current = true
    setSending(true)
    try {
      // One transfer at a time, as the game keeps one upload per player.
      for (const file of files) {
        const { name, blob, scaled } = await prepareImage(file, data.maxDimension, data.maxBytes)
        const note = scaled ? " " + tr("（{0} 超过 5 MB，已缩小到 {1}×{2} 像素）", file.name, scaled[0], scaled[1]) : ""
        if (!alive.current) break
        await post({ name, data: await base64(blob), folder })
        setMessage(tr("正在上传图片，完成后会出现在图片库中。"))
        // Wait for the game's asynchronous upload result before starting another file.
        const started = Date.now()
        while (alive.current) {
          const response = await fetch(endpoint("images"), { headers, cache: "no-store" })
          if (!response.ok) throw new Error(tr("发送失败，游戏可能已经关闭"))
          const next: Library = await response.json()
          if (!alive.current) break
          setData(next)
          if (!next.busy) { setMessage(next.message + note); break }
          if (Date.now() - started > 65000) throw new Error(tr("图片上传超时，请重试。"))
          await new Promise(resolve => window.setTimeout(resolve, 500))
        }
      }
    } catch (error) { if (alive.current) setMessage((error as Error).message || tr("无法读取图片。")) }
    finally { busy.current = false; if (alive.current) setSending(false) }
  }
  return <div className="flex min-h-0 flex-1 flex-col" data-image-library>
    <div className="flex items-center gap-3 border-b border-grid p-3">
      <b>{tr("图片库")}</b>
      <input className="h-7 min-w-0 flex-1 rounded-md border bg-panel px-2" placeholder={tr("搜索图片名称")} value={search} onChange={event => setSearch(event.target.value)} />
      <select className="h-7 rounded-md border bg-panel" aria-label={tr("上传者")} value={uploader} onChange={event => setUploader(event.target.value)}><option value="">{tr("所有上传者")}</option>{[...new Set(data?.entries.map(item => item.uploaderName) || [])].map(name => <option key={name}>{name}</option>)}</select>
      <input type="file" ref={input} accept=".png,.jpg,.jpeg,.gif,image/png,image/jpeg,image/gif" multiple hidden onChange={event => { const files = [...event.target.files || []]; event.target.value = ""; askUpload(files) }} />
      <button className="btn btn-primary" disabled={!data?.canUpload || sending || data.busy} onClick={() => input.current?.click()}>{tr("导入图片")}</button>
    </div>
    <p className="m-0 p-3 text-[12px] text-dim">{tr("导入 PNG、JPEG 或 GIF，宽高最多 2048 像素，上传内容不超过 5 MB。GIF 保留动画。用 MTR 刷子右键原版画选图和调整位置、尺寸。图片使用内部名称。")}</p>
    {data?.maxTotalBytes !== undefined && <p className="m-0 px-3 pb-2 text-[12px] text-dim">{tr("已用 {0} MB / 总容量 {1} MB", ((data.totalBytes || 0) / 1048576).toFixed(1), data.maxTotalBytes / 1048576)}</p>}
    {pending && <UploadTarget files={pending} targets={uploadTargets(tree, self)} value={uploadFolder} onChange={setUploadFolder} onCancel={() => setPending(null)} onConfirm={() => { const files = pending; setPending(null); upload(files, uploadFolder) }} />}
    <div className="flex min-h-0 flex-1 flex-col md:flex-row" onDragOver={event => { if (![...event.dataTransfer.types].includes("Files")) return; event.preventDefault(); event.dataTransfer.dropEffect = data?.canUpload && !sending && !data.busy ? "copy" : "none" }} onDrop={event => { if (![...event.dataTransfer.types].includes("Files")) return; event.preventDefault(); if (!sending && !data?.busy) askUpload([...event.dataTransfer.files]) }}>
      <aside className="flex max-h-[30%] shrink-0 flex-col border-b border-grid p-2 md:max-h-none md:w-[220px] md:border-b-0 md:border-r">
        <FolderTree roots={tree} current={current} onSelect={ref => { setCurrent(ref); setChecked(new Set()) }} canOrganise={canOrganise} dragging={dragging} onAction={folderAction} />
      </aside>
      <div className="scroll-thin min-h-0 flex-1 overflow-auto p-3">
        {shown.map(item => <div key={item.name} data-entry={item.name} className={"mb-1 flex w-full items-center gap-2 rounded-md border p-2 text-left " + (selected === item.name ? "tint-sel" : "bg-panel")}
          draggable={canOrganise(item.uploader || "")}
          onDragStart={event => {
            const moving = (checked.has(item.name) ? shown.filter(each => checked.has(each.name)) : [item]).map(each => ({ ...each, key: each.name }))
            event.dataTransfer.setData(DRAG_TYPE, JSON.stringify(moving.map(each => each.key)))
            event.dataTransfer.effectAllowed = "move"
            setDragging(moving)
          }}
          onDragEnd={() => setDragging(null)}>
          <input type="checkbox" aria-label={tr("选中")} checked={checked.has(item.name)} onChange={event => setChecked(set => { const next = new Set(set); if (event.target.checked) next.add(item.name); else next.delete(item.name); return next })} />
          <button className="flex min-w-0 flex-1 items-center justify-between text-left" onClick={() => setSelected(item.name)}><span className="truncate">{item.name}{item.folder ? <span className="text-[11px] text-dim"> · {item.folder.split("/").join(" / ")}</span> : null}</span><span className="ml-2 whitespace-nowrap text-[12px] text-dim">{item.width} × {item.height}</span></button>
        </div>)}
        {data?.entries.length === 0 && <p>{tr("图片库还没有图片。")}</p>}
        {data && data.entries.length > 0 && !shown.length && <p className="text-[13px] text-dim">{current ? tr("这个文件夹里没有文件。") : tr("没有符合条件的文件。")}</p>}
      </div>
      <aside className="flex min-h-0 flex-1 flex-col gap-3 border-l border-grid p-3">
        {entry && <>
          <b className="break-all">{entry.name}</b>
          {preview ? <img className="max-h-72 max-w-full object-contain" src={preview} alt={entry.name} /> : <p>{tr("正在下载图片预览…")}</p>}
          <span className="text-[12px] text-dim">{entry.width} × {entry.height} · {Math.ceil(entry.size / 1024)} KB</span>
          <span className="text-[12px] text-dim">{tr("上传者")}: {entry.uploaderName} · {new Date(entry.uploadedAt).toLocaleString()}</span>
          <code className="break-all text-[12px]">{entry.name}</code>
          {entry.canDelete && <ConfirmButton confirm={tr("删除这张图片？使用它的画将恢复原版显示。")} onConfirm={() => { setMessage(""); post({ action: "delete", name: entry.name }).catch(error => setMessage(error.message)) }}>{tr("删除")}</ConfirmButton>}
        </>}
        {(message || data?.message) && <p className="text-[12px]" role="status">{message || data?.message}</p>}
      </aside>
    </div>
  </div>
}
