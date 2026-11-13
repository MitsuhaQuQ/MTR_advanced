/**
 * The media library, written for the new page (it ran the old media.js before): the announcement audio the server keeps,
 * searched and filtered on the left, the file chosen on the right with a player, its names to copy, a new sound ID and
 * deleting. Files are added with the upload button or dropped on the list; a file whose name can't be a sound ID is given
 * one in the page (the old page asked with prompt(), which some browsers block), as deleting asks in the page.
 *
 * The same endpoints as before: GET api/audio (the list, read every two seconds while the library shows), POST api/audio
 * {action: upload | delete | soundId | folder}, GET api/audio-file?hash= (a file to play, once this computer has it).
 *
 * Folders (0.74.0): the tree on the left (MediaFolders.tsx) shows a folder's files; files are dragged onto a folder to
 * move them, several at once when ticked; before files go up the page asks which of the player's folders they go to.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { key } from "../api/client"
import { ConfirmButton } from "../ui/bits"
import { tr } from "../i18n"
import { convertAudio, isWav, isMp3 } from "../core/audioConversion"
import { ImagesLibrary } from "./ImagesLibrary"
import { FolderTree, UploadTarget, type FolderAction } from "./MediaFolders"
import { buildTree, DRAG_TYPE, filesIn, findNode, uploadTargets, type FolderRef, type KeptFolder } from "../core/mediaFolders"

const POLL_MILLIS = 2000, MAX_MESSAGES = 8, MAX_SOUND_ID = 64
const SOUND_ID = /^[a-z0-9_.-]+(\/[a-z0-9_.-]+)*$/
export const isSoundId = (id: string) => id.length <= MAX_SOUND_ID && SOUND_ID.test(id)
/** As the game makes one: the file name without .ogg in lowercase, spaces as _, or nothing for a name in Chinese */
export const soundIdFromName = (name: string) => { const id = name.replace(/\.ogg$/i, "").toLowerCase().replace(/ /g, "_"); return isSoundId(id) ? id : "" }
export const formatSize = (bytes: number) => (bytes >= 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1) + " MB" : Math.max(1, Math.round(bytes / 1024)) + " KB")
const formatDuration = (millis: number) => { const seconds = Math.round(millis / 100) / 10; return seconds >= 60 ? Math.floor(seconds / 60) + ":" + String(Math.round(seconds % 60)).padStart(2, "0") : seconds.toFixed(1) + tr(" 秒") }
const formatClock = (seconds: number) => Math.floor(seconds / 60) + ":" + String(Math.floor(seconds % 60)).padStart(2, "0")
const formatTime = (millis: number) => { const d = new Date(millis), pad = (n: number) => String(n).padStart(2, "0"); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}` }
const takes = (file: { name: string; type: string }) => /\.ogg$/i.test(file.name) || /ogg/.test(file.type) || isWav(file) || isMp3(file)

type Entry = { key: string; name: string; reference: string; sound?: string; soundId: string; hash: string; uploader?: string; uploaderName: string; folder?: string; size: number; durationMillis: number; uploadedAt: number; cached: boolean; downloading: boolean; mine: boolean; canDelete: boolean }
type Library = { language?: string; canUpload: boolean; canManage?: boolean; maxFileBytes: number; maxTotalBytes: number; totalBytes: number; maxSeconds: number; requests: { name: string; done: boolean; ok: boolean; message: string }[]; entries: Entry[]; folders?: KeptFolder[]; self?: string }
type Message = { name: string; message: string; ok: boolean }
/** A file waiting for its sound ID before it goes up, and the folder it goes to */
type Waiting = { file: File; soundId: string; bad: boolean; folder: string }

const url = (path: string) => new URL("/" + path, location.origin).toString()
async function fetchJson(path: string) {
  const response = await fetch(url(path), { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" })
  if (!response.ok) throw new Error(String(response.status))
  return response.json()
}
async function sendJson(path: string, body: unknown) {
  const response = await fetch(url(path), { method: "POST", headers: { "X-MTR-Editor-Key": key, "Content-Type": "application/json" }, body: JSON.stringify(body), cache: "no-store" })
  if (!response.ok) throw new Error(String(response.status))
  return response.json()
}
const failed = (e: unknown) => ((e as Error).message === "413" ? tr("文件太大") : tr("发送失败，游戏可能已经关闭"))
const readBase64 = (file: File) => new Promise<string>((resolve, reject) => {
  const reader = new FileReader()
  reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ""))
  reader.onerror = () => reject(new Error("read"))
  reader.readAsDataURL(file)
})

export function MediaLibrary() {
  const [kind, setKind] = useState<"audio" | "images">("audio")
  return <div className="flex min-h-0 flex-1 flex-col">
    <nav className="flex gap-2 border-b border-grid bg-panel px-3 py-2">
      <button className="btn" aria-pressed={kind === "audio"} onClick={() => setKind("audio")}>{tr("音频")}</button>
      <button className="btn" aria-pressed={kind === "images"} onClick={() => setKind("images")}>{tr("图片")}</button>
    </nav>
    {kind === "audio" ? <AudioLibrary /> : <ImagesLibrary />}
  </div>
}

function AudioLibrary() {
  const [data, setData] = useState<Library | null>(null)
  const [connection, setConnection] = useState<"connecting" | "ok" | "key" | "world" | "offline">("connecting")
  const [selected, setSelected] = useState<string | null>(null)
  const [search, setSearch] = useState("")
  const [uploader, setUploader] = useState("")
  const [missingOnly, setMissingOnly] = useState(false)
  const [messages, setMessages] = useState<Message[]>([])
  const [waiting, setWaiting] = useState<Waiting[]>([])
  const [drop, setDrop] = useState<{ refused: boolean; text: string } | null>(null)
  // Folders: the one open (null: every file), the files ticked, the ones being dragged, and files waiting for a folder
  const [current, setCurrent] = useState<FolderRef | null>(null)
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [dragging, setDragging] = useState<Entry[] | null>(null)
  const [pending, setPending] = useState<File[] | null>(null)
  const [uploadFolder, setUploadFolder] = useState("")
  const fileInput = useRef<HTMLInputElement>(null)
  const [converting, setConverting] = useState<{ name: string; percent: number } | null>(null)
  const conversion = useRef<AbortController | null>(null)
  const busy = useRef(false)
  useEffect(() => () => conversion.current?.abort(), [])
  const note = (name: string, message: string, ok = false) => setMessages(list => [{ name, message, ok }, ...list].slice(0, MAX_MESSAGES))

  const load = useCallback(async () => {
    try {
      const next: Library = await fetchJson("api/audio")
      setData(next)
      setConnection("ok")
    } catch (e) {
      const status = (e as Error).message
      setConnection(status === "401" ? "key" : status === "503" ? "world" : "offline")
    }
  }, [])
  // Read while the library shows and the page is in view
  useEffect(() => {
    let timer = 0, stopped = false
    const poll = async () => {
      if (!document.hidden) await load()
      if (!stopped) timer = window.setTimeout(poll, POLL_MILLIS)
    }
    poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [load])

  const tree = useMemo(() => (data ? buildTree(data.entries, data.folders || [], data.self || "") : []), [data])
  // A folder that is gone (deleted, renamed elsewhere) shows every file again
  useEffect(() => { if (data && current && !findNode(tree, current)) setCurrent(null) }, [tree])
  const self = data?.self || ""
  const canOrganise = (owner: string) => Boolean(data && owner && (owner === self || data.canManage))
  const entries = useMemo(() => {
    if (!data) return []
    const text = search.trim().toLowerCase()
    // A folder's own files; searching, everything below it too
    return filesIn(data.entries, current, Boolean(text))
      .filter(entry => !uploader || entry.uploaderName === uploader)
      .filter(entry => !missingOnly || !entry.cached)
      .filter(entry => !text || [entry.name, entry.reference, entry.sound || ""].some(value => value.toLowerCase().includes(text)))
      .sort((a, b) => b.uploadedAt - a.uploadedAt)
  }, [data, search, uploader, missingOnly, current])
  const uploaders = data ? [...new Set(data.entries.map(entry => entry.uploaderName))].sort((a, b) => a.localeCompare(b)) : []
  const entry = data && data.entries.find(item => item.key === selected)
  const canUpload = Boolean(data && data.canUpload && data.maxFileBytes > 0)

  // ---------- Playing ----------
  const player = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState<string | null>(null)
  const [position, setPosition] = useState(0)
  const playingUrl = useRef<string | null>(null)
  const stop = () => {
    player.current?.pause()
    if (playingUrl.current) URL.revokeObjectURL(playingUrl.current)
    playingUrl.current = null
    setPlaying(null)
    setPosition(0)
  }
  useEffect(() => stop, []) // Nothing plays on behind another layer
  const play = async (hash: string) => {
    const again = playing === hash
    stop()
    if (again || !player.current) return
    try {
      const response = await fetch(url("api/audio-file?hash=" + hash), { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" })
      if (!response.ok) throw new Error(String(response.status))
      playingUrl.current = URL.createObjectURL(await response.blob())
      player.current.src = playingUrl.current
      setPlaying(hash)
      await player.current.play()
    } catch (e) {
      setPlaying(null)
      note("", (e as Error).message === "404" ? tr("本机还没有这个文件，正在下载") : tr("这个浏览器无法播放这个文件"))
    }
  }

  // ---------- Uploading ----------
  const upload = async (file: File, soundId: string, folder: string) => {
    try {
      const result = await sendJson("api/audio", { action: "upload", name: file.name, soundId, folder, data: await readBase64(file) })
      if (!result.ok) note(file.name, result.message)
    } catch (e) {
      note(file.name, failed(e))
    }
    load()
  }
  /** Files chosen or dropped: first asks which folder they go to (the one open, if it is the player's) */
  const askUpload = (files: File[]) => {
    if (!data || !canUpload || !files.length) return
    setUploadFolder(current && current.owner === self ? current.path : "")
    setPending(files)
  }
  const uploadFiles = async (files: File[], folder: string) => {
    if (!data || !canUpload || busy.current) return
    busy.current = true
    const controller = new AbortController()
    conversion.current = controller
    const ask: Waiting[] = []
    try {
      for (const original of files) {
        if (controller.signal.aborted) break
        let file = original
        if (!takes(file)) { note(file.name, tr("只收 Ogg Vorbis、WAV 或 MP3 文件")); continue }
        if (isWav(file) || isMp3(file)) {
          try {
            setConverting({ name: file.name, percent: 0 })
            file = await convertAudio(file, data.maxSeconds, data.maxFileBytes, percent => setConverting({ name: original.name, percent }), controller.signal)
          } catch (e) {
            if (controller.signal.aborted) break
            note(original.name, (e as Error).message)
            continue
          } finally { setConverting(null) }
        }
        if (file.size > data.maxFileBytes) { note(file.name, tr("文件不能超过 {0}", formatSize(data.maxFileBytes))); continue }
        // Match the converted name so updates keep an existing Ogg file's sound ID.
        const fileName = /\.ogg$/i.test(file.name) ? file.name : file.name + ".ogg"
        if (data.entries.some(each => each.mine && each.name.toLowerCase() === fileName.toLowerCase())) upload(file, "", folder)
        else ask.push({ file, soundId: soundIdFromName(file.name), bad: false, folder })
      }
      if (!controller.signal.aborted && ask.length) setWaiting(list => [...list, ...ask])
    } finally { busy.current = false; conversion.current = null; setConverting(null) }
  }
  const sendWaiting = (index: number) => {
    const item = waiting[index]
    const id = item.soundId.trim()
    if (!isSoundId(id)) { setWaiting(list => list.map((each, i) => (i === index ? { ...each, bad: true } : each))); return }
    setWaiting(list => list.filter((_, i) => i !== index))
    upload(item.file, id, item.folder)
  }

  // ---------- Acting on a file ----------
  const [renaming, setRenaming] = useState<{ key: string; soundId: string; bad: boolean } | null>(null)
  const act = async (name: string, body: unknown) => {
    try {
      const result = await sendJson("api/audio", body)
      if (!result.ok) note(name, result.message)
    } catch (e) {
      note(name, failed(e))
    }
    load()
  }
  const folderAction = (action: FolderAction, label: string) => { setChecked(new Set()); act(label, { action: "folder", ...action }) }
  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      note(text, tr("已复制"), true)
    } catch {
      note(text, tr("浏览器不让复制，请手动选中复制"))
    }
  }

  const usage = data && data.maxTotalBytes > 0 ? data.totalBytes / data.maxTotalBytes : 0
  const problems: Record<string, string> = { key: tr("页面的链接已经失效，请在游戏里重新打开高级编辑器。"), world: tr("游戏没有响应，可能不在世界里。"), offline: tr("连不上游戏，游戏可能已经关闭。") }
  const requests = [...messages.map(m => ({ name: m.name, done: true, ok: m.ok, message: m.message })), ...(data && data.requests ? data.requests.slice().reverse() : [])].slice(0, MAX_MESSAGES)
  const localText = (e: Entry) => (e.cached ? tr("有") : e.downloading ? tr("下载中") : tr("没有"))

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col" data-media-library>
      <div className="flex shrink-0 flex-wrap items-center gap-3 border-b border-grid bg-panel px-3 py-2">
        <span className="text-[13px] font-medium">{tr("媒体库")} · {tr("音频")}</span>
        <span className="flex-1" />
        {data && data.maxTotalBytes > 0 && (
          <span className="flex items-center gap-2 text-[12px] text-dim" title={tr("服务器上音频占用的空间")}>
            <span className="h-1.5 w-28 overflow-hidden rounded-pill bg-panel2"><span className="block h-full" style={{ width: Math.min(100, usage * 100).toFixed(1) + "%", background: usage >= 1 ? "var(--warn)" : usage >= 0.9 ? "var(--caution)" : "var(--accent)" }} /></span>
            {tr("已用 {0} / {1}", formatSize(data.totalBytes), formatSize(data.maxTotalBytes))}
          </span>
        )}
        <input ref={fileInput} type="file" accept=".ogg,.wav,.mp3,audio/ogg,audio/wav,audio/x-wav,audio/mpeg,audio/mp3" multiple hidden onChange={e => { const files = [...(e.target.files || [])]; e.target.value = ""; askUpload(files) }} />
        <button className="btn btn-primary" disabled={!canUpload || connection !== "ok" || !!converting} title={canUpload ? tr("选择 Ogg Vorbis、WAV 或 MP3 文件，也可以直接拖到列表上") : tr("没有上传音频的权限")} onClick={() => fileInput.current?.click()}>{tr("上传音频")}</button>
      </div>
      {problems[connection] && <div className="tint-warn shrink-0 px-3 py-1.5 text-[13px] text-warn" role="alert">{problems[connection]}</div>}
      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        {/* The folders */}
        <aside className="flex max-h-[30%] shrink-0 flex-col border-b border-grid p-2 lg:max-h-none lg:w-[220px] lg:border-b-0 lg:border-r">
          <FolderTree roots={tree} current={current} onSelect={ref => { setCurrent(ref); setChecked(new Set()) }} canOrganise={canOrganise} dragging={dragging} onAction={folderAction} />
        </aside>
        {/* The list */}
        <section className="relative flex min-h-0 min-w-0 flex-1 flex-col"
          onDragOver={e => {
            if (![...e.dataTransfer.types].includes("Files")) return
            e.preventDefault()
            // The browser tells the type, not the name, while dragging; an unknown type is let through and checked on the drop
            const wrong = [...e.dataTransfer.items].filter(item => item.kind === "file").some(item => item.type && !takes({ name: "", type: item.type }))
            const refused = !canUpload || wrong || !!converting
            e.dataTransfer.dropEffect = refused ? "none" : "copy"
            setDrop({ refused, text: !canUpload ? tr("没有上传音频的权限") : wrong ? tr("只收 Ogg Vorbis、WAV 或 MP3 文件") : converting ? tr("正在转换音频…") : tr("松开上传到{0}", tr("音频")) })
          }}
          onDragLeave={e => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setDrop(null) }}
          onDrop={e => {
            if (![...e.dataTransfer.types].includes("Files")) return
            e.preventDefault()
            setDrop(null)
            if (canUpload) askUpload([...e.dataTransfer.files])
          }}>
          <div className="flex shrink-0 flex-wrap items-center gap-2 px-3 py-2">
            <input className="h-7 min-w-[160px] flex-1 rounded-md border border-ctl bg-panel px-2 text-[13px]" type="search" placeholder={tr("搜索名字、引用名或声音")} value={search} onChange={e => setSearch(e.target.value)} aria-label={tr("搜索")} />
            <select className="h-7 rounded-md border border-ctl bg-panel px-1 text-[13px]" value={uploaders.includes(uploader) ? uploader : ""} onChange={e => setUploader(e.target.value)} aria-label={tr("上传者")}>
              <option value="">{tr("所有上传者")}</option>
              {uploaders.map(name => <option key={name} value={name}>{name}</option>)}
            </select>
            <button className="btn" aria-pressed={missingOnly} style={missingOnly ? { borderColor: "var(--accent)" } : undefined} onClick={() => setMissingOnly(!missingOnly)}>{tr("只看本机没有的")}</button>
          </div>
          {data && <p className="m-0 px-3 pb-1 text-[12px] text-dim">{data.maxFileBytes > 0 ? tr("支持 Ogg Vorbis、WAV 和 MP3；WAV/MP3 在浏览器内转为单声道 Ogg（24 kHz）。上传文件不超过 {0}、{1} 秒。", formatSize(data.maxFileBytes), data.maxSeconds) + (data.canUpload ? "" : tr("你没有上传音频的权限。")) : tr("服务器没有开放上传音频。")} {tr("也可以把文件拖到列表上。")}</p>}
          {pending && <UploadTarget files={pending} targets={uploadTargets(tree, self)} value={uploadFolder} onChange={setUploadFolder} onCancel={() => setPending(null)} onConfirm={() => { const files = pending; setPending(null); uploadFiles(files, uploadFolder) }} />}
          {converting && <div className="mx-3 mb-2 flex items-center gap-2 text-[12px]" role="status"><span>{tr("正在转换 {0}：{1}%", converting.name, converting.percent)}</span><button className="btn" onClick={() => conversion.current?.abort()}>{tr("取消转换")}</button></div>}
          {waiting.length > 0 && (
            <div className="mx-3 mb-2 flex flex-col gap-1.5 rounded-lg border border-brand p-2 text-[12px]">
              {waiting.map((item, i) => (
                <div key={item.file.name + i} className="flex flex-wrap items-center gap-1.5">
                  <span className="min-w-0 truncate font-medium">{item.file.name}</span>
                  <span className="text-dim">{tr("声音 ID（/playsound 用它播放）")}</span>
                  <input className="h-6 w-40 rounded-md border bg-panel px-1" style={{ borderColor: item.bad ? "var(--warn)" : "var(--control-line)" }} value={item.soundId} aria-label={tr("声音 ID")}
                    onChange={e => setWaiting(list => list.map((each, k) => (k === i ? { ...each, soundId: e.target.value, bad: false } : each)))}
                    onKeyDown={e => { if (e.key === "Enter") sendWaiting(i) }} />
                  <button className="btn btn-primary h-6 px-2 text-[12px]" onClick={() => sendWaiting(i)}>{tr("上传")}</button>
                  <button className="btn h-6 px-2 text-[12px]" onClick={() => setWaiting(list => list.filter((_, k) => k !== i))}>{tr("不传了")}</button>
                  {item.bad && <span className="w-full text-warn">{tr("声音 ID 只能用小写字母、数字和 _ - . /，最长 {0} 个字符。", MAX_SOUND_ID)}</span>}
                </div>
              ))}
            </div>
          )}
          <div className="scroll-thin min-h-0 flex-1 overflow-auto px-3">
            <table className="w-full border-collapse text-[13px]">
              <thead className="sticky top-0 bg-panel text-left text-[12px] text-dim">
                <tr><th className="w-6 font-normal" /><th className="py-1 font-normal">{tr("名字")}</th><th className="font-normal">{tr("时长")}</th><th className="font-normal">{tr("大小")}</th><th className="font-normal">{tr("上传者")}</th><th className="font-normal">{tr("本机")}</th><th /></tr>
              </thead>
              <tbody>
                {entries.map((e, i) => (
                  <tr key={e.key} tabIndex={0} aria-selected={e.key === selected} data-entry={e.key}
                    className={"cursor-pointer border-t border-grid " + (e.key === selected ? "tint-sel" : "hover:bg-panel2")}
                    onClick={() => setSelected(e.key)}
                    draggable={canOrganise(e.uploader || "")}
                    onDragStart={event => {
                      // The ticked files if this is one of them, else this one
                      const moving = checked.has(e.key) ? entries.filter(each => checked.has(each.key)) : [e]
                      event.dataTransfer.setData(DRAG_TYPE, JSON.stringify(moving.map(each => each.key)))
                      event.dataTransfer.effectAllowed = "move"
                      setDragging(moving)
                    }}
                    onDragEnd={() => setDragging(null)}
                    onKeyDown={event => {
                      if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelected(e.key) }
                      else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                        event.preventDefault()
                        const next = entries[i + (event.key === "ArrowDown" ? 1 : -1)]
                        if (next) { setSelected(next.key); (event.currentTarget.parentElement?.children[i + (event.key === "ArrowDown" ? 1 : -1)] as HTMLElement | undefined)?.focus() }
                      }
                    }}>
                    <td onClick={event => event.stopPropagation()}><input type="checkbox" aria-label={tr("选中")} checked={checked.has(e.key)} onChange={event => setChecked(set => { const next = new Set(set); if (event.target.checked) next.add(e.key); else next.delete(e.key); return next })} /></td>
                    <td className="py-1.5">{e.name}<div className="text-[11px] text-dim">{e.reference}{e.folder ? " · " + e.folder.split("/").join(" / ") : ""}</div></td>
                    <td className="num">{formatDuration(e.durationMillis)}</td>
                    <td className="num">{formatSize(e.size)}</td>
                    <td>{e.uploaderName}</td>
                    <td className={e.cached ? "" : "text-caution"}>{localText(e)}</td>
                    <td className="text-right"><button className="btn h-6 px-2 text-[12px]" disabled={!e.cached} title={e.cached ? "" : tr("本机还没有这个文件")} onClick={event => { event.stopPropagation(); play(e.hash) }}>{playing === e.hash ? tr("停止") : tr("试听")}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
            {data && !entries.length && <p className="text-[13px] text-dim">{data.entries.length ? (current ? tr("这个文件夹里没有文件。") : tr("没有符合条件的文件。")) : tr("服务器上还没有报站音频。")}</p>}
            {!data && connection === "connecting" && <p className="text-[13px] text-dim">{tr("正在连接游戏…")}</p>}
          </div>
          {drop && <div className={"pointer-events-none absolute inset-2 flex items-center justify-center rounded-lg border-2 border-dashed text-[14px] font-medium " + (drop.refused ? "border-warn text-warn" : "border-brand text-brand")} style={{ background: "color-mix(in srgb, var(--panel) 85%, transparent)" }}>{drop.text}</div>}
        </section>

        {/* The file chosen, and what happened lately */}
        <aside className="scroll-thin flex max-h-[45%] shrink-0 flex-col gap-3 overflow-y-auto border-t border-grid bg-panel p-3 lg:max-h-none lg:w-[340px] lg:border-l lg:border-t-0" aria-label={tr("音频")}>
          {!entry ? <p className="m-0 text-[12px] text-dim">{tr("点列表里的一个文件，在这里试听、复制它的名字、改名或删除。")}</p> : <>
            <div className="flex flex-col gap-2 rounded-lg border border-grid p-3">
              <b className="text-[14px]">{entry.name}</b>
              <div className="flex items-center gap-2 text-[12px]">
                <button className="btn h-7 px-3" disabled={!entry.cached} title={entry.cached ? "" : tr("本机还没有这个文件")} onClick={() => play(entry.hash)}>{playing === entry.hash ? tr("停止") : tr("试听")}</button>
                <span className="num text-dim">{formatClock(playing === entry.hash ? position : 0)}</span>
                <span className="h-1.5 flex-1 overflow-hidden rounded-pill bg-panel2"><span className="block h-full bg-brand" style={{ width: playing === entry.hash && entry.durationMillis ? Math.min(100, position / (entry.durationMillis / 1000) * 100) + "%" : "0" }} /></span>
                <span className="num text-dim">{formatClock(entry.durationMillis / 1000)}</span>
              </div>
              <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[13px]">
                <dt className="text-dim">{tr("时长")}</dt><dd className="m-0">{formatDuration(entry.durationMillis)}</dd>
                <dt className="text-dim">{tr("大小")}</dt><dd className="m-0">{formatSize(entry.size)}</dd>
                <dt className="text-dim">{tr("上传者")}</dt><dd className="m-0">{entry.uploaderName}</dd>
                <dt className="text-dim">{tr("上传时间")}</dt><dd className="m-0 num">{formatTime(entry.uploadedAt)}</dd>
                <dt className="text-dim">{tr("本机")}</dt><dd className="m-0">{localText(entry)}</dd>
              </dl>
            </div>
            <div className="flex flex-col gap-2 rounded-lg border border-grid p-3 text-[12px]">
              {([[tr("引用名"), entry.reference, tr("填进报站器的“音频文件”栏")], ...(entry.sound ? [["/playsound", entry.sound, tr("/playsound 用这个名字播放，报站器的“音频文件”栏也可以填它")]] : [])] as string[][]).map(([label, value, title]) => (
                <div key={label} className="flex items-center gap-2">
                  <span className="w-16 shrink-0 text-dim" title={title}>{label}</span>
                  <code className="min-w-0 flex-1 truncate rounded-sm bg-panel2 px-1.5 py-0.5">{value}</code>
                  <button className="btn h-6 px-2 text-[12px]" onClick={() => copy(value)}>{tr("复制")}</button>
                </div>
              ))}
              <p className="m-0 text-dim">{tr("每个音频在游戏里也是一个声音，可以用 /playsound 播放，例如 /playsound mtr_audio:<上传者>/<声音 ID> block @a。")}</p>
            </div>
            {entry.canDelete && (
              <div className="flex flex-col gap-2">
                {renaming && renaming.key === entry.key ? (
                  <div className="flex flex-wrap items-center gap-1.5 text-[12px]">
                    <input className="h-7 min-w-0 flex-1 rounded-md border bg-panel px-1.5" style={{ borderColor: renaming.bad ? "var(--warn)" : "var(--control-line)" }} value={renaming.soundId} aria-label={tr("新声音 ID")} onChange={e => setRenaming({ ...renaming, soundId: e.target.value, bad: false })} />
                    <button className="btn btn-primary h-7" onClick={() => {
                      const id = renaming.soundId.trim()
                      if (!isSoundId(id)) { setRenaming({ ...renaming, bad: true }); return }
                      setRenaming(null)
                      if (id !== entry.soundId) act(entry.name, { action: "soundId", key: entry.key, soundId: id })
                    }}>{tr("确定")}</button>
                    <button className="btn h-7" onClick={() => setRenaming(null)}>{tr("取消")}</button>
                    <span className="w-full text-dim">{tr("用旧 ID 的 /playsound 和报站器要改成新的")}</span>
                    {renaming.bad && <span className="w-full text-warn">{tr("声音 ID 只能用小写字母、数字和 _ - . /，最长 {0} 个字符。", MAX_SOUND_ID)}</span>}
                  </div>
                ) : (
                  <div className="flex flex-wrap gap-1.5">
                    <button className="btn" onClick={() => setRenaming({ key: entry.key, soundId: entry.soundId, bad: false })}>{tr("改声音 ID")}</button>
                    <ConfirmButton confirm={tr("从服务器删除“{0}”？用这个名字的报站器都会不出声，不能撤销。", entry.reference)} onConfirm={() => { if (playing === entry.hash) stop(); act(entry.name, { action: "delete", key: entry.key }) }}>{tr("删除")}</ConfirmButton>
                  </div>
                )}
              </div>
            )}
          </>}
          {requests.length > 0 && (
            <div className="flex flex-col gap-1">
              <h3 className="m-0 text-[12px] font-medium text-dim">{tr("最近的操作")}</h3>
              <ul className="m-0 flex list-none flex-col gap-0.5 p-0 text-[12px]">
                {requests.map((item, i) => (
                  <li key={i} className={!item.done ? "text-dim" : item.ok ? "" : "text-warn"}>{item.name && <b className="mr-1">{item.name}</b>}{item.done ? item.message : tr("正在发送…")}</li>
                ))}
              </ul>
            </div>
          )}
        </aside>
      </div>
      <footer className="flex shrink-0 gap-4 border-t border-grid bg-panel px-3 py-1 text-[12px] text-dim">
        {data ? <>
          <span>{tr("音频")} <b className="text-ink">{data.entries.length}</b> {tr("个")}</span>
          <span>{tr("本机已有")} <b className="text-ink">{data.entries.filter(e => e.cached).length}</b> {tr("个")}</span>
          {data.entries.some(e => e.downloading) && <span>{tr("正在下载…")}</span>}
        </> : <span>{tr("正在连接游戏…")}</span>}
      </footer>
      <audio ref={player} hidden onTimeUpdate={e => setPosition(e.currentTarget.currentTime)} onEnded={stop} />
    </div>
  )
}
