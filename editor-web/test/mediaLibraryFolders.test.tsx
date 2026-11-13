// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from "vitest"
import { act, createElement } from "react"
import { createRoot, type Root } from "react-dom/client"
import { MediaLibrary } from "../src/views/MediaLibrary"
import { ImagesLibrary } from "../src/views/ImagesLibrary"

// Folders in the media library (0.74.0): choosing a folder, dragging files into it, making and deleting folders, and the
// folder an upload goes to
let root: Root | undefined
let fetchBefore: typeof fetch
afterEach(async () => { if (root) await act(async () => root?.unmount()); root = undefined; document.body.innerHTML = ""; if (fetchBefore) globalThis.fetch = fetchBefore })

const audio = (key: string, uploader: string, uploaderName: string, folder: string, mine: boolean) => ({
  key, name: key.split(":")[1], reference: key, soundId: "x", hash: key.length.toString(16).padStart(64, "0"), uploader, uploaderName, folder,
  size: 1000, durationMillis: 1000, uploadedAt: 1, cached: true, downloading: false, mine, canDelete: mine,
})

async function show(element: Parameters<typeof createElement>[0], library: unknown, posts: unknown[]) {
  fetchBefore = globalThis.fetch
  globalThis.fetch = (async (_: string, init?: RequestInit) => {
    if (init && init.method === "POST") { posts.push(JSON.parse(String(init.body))); return new Response(JSON.stringify({ ok: true }), { status: 200 }) }
    return new Response(JSON.stringify(library), { status: 200 })
  }) as typeof fetch
  const host = document.createElement("div")
  document.body.appendChild(host)
  await act(async () => { root = createRoot(host); root.render(createElement(element as never)); await new Promise(resolve => setTimeout(resolve, 20)) })
  return host
}
const folderButton = (host: HTMLElement, id: string) => host.querySelector(`[data-folder='${id}']`) as HTMLElement
const buttonNamed = (host: ParentNode, text: string) => [...host.querySelectorAll("button")].find(b => b.textContent === text) as HTMLElement
/** A drag event with a data transfer, which happy-dom doesn't make */
function drag(target: Element, type: string, data: Map<string, string>) {
  const event = new Event(type, { bubbles: true, cancelable: true })
  Object.defineProperty(event, "dataTransfer", { value: { types: [...data.keys()], setData: (k: string, v: string) => data.set(k, v), getData: (k: string) => data.get(k) || "", dropEffect: "", effectAllowed: "" } })
  target.dispatchEvent(event)
}

describe("folders in the media library", () => {
  it("shows a folder's files, moves dragged files into a folder, and makes and deletes folders", async () => {
    const posts: any[] = []
    const library = { canUpload: true, canManage: false, maxFileBytes: 1 << 20, maxTotalBytes: 1 << 26, totalBytes: 0, maxSeconds: 60, requests: [], self: "u1",
      entries: [audio("steve:a.ogg", "u1", "Steve", "", true), audio("steve:b.ogg", "u1", "Steve", "1号线", true), audio("alex:c.ogg", "u2", "Alex", "", false)],
      folders: [{ owner: "u1", ownerName: "Steve", path: "1号线/上行" }] }
    const host = await show(MediaLibrary, library, posts)
    const rows = () => [...host.querySelectorAll("tr[data-entry]")].map(r => r.getAttribute("data-entry"))
    expect(rows().length).toBe(3)
    // The player's own folders first
    expect([...host.querySelectorAll("[data-folder]")].map(b => b.getAttribute("data-folder"))).toEqual(["u1:", "u1:1号线", "u1:1号线/上行", "u2:"])
    await act(async () => folderButton(host, "u1:").click())
    expect(rows()).toEqual(["steve:a.ogg"])

    // Dragging a.ogg onto 1号线/上行 moves it there; someone else's folder doesn't take it
    const data = new Map<string, string>()
    await act(async () => drag(host.querySelector("tr[data-entry='steve:a.ogg']")!, "dragstart", data))
    await act(async () => drag(folderButton(host, "u2:"), "drop", data))
    expect(posts.length).toBe(0)
    await act(async () => { drag(folderButton(host, "u1:1号线/上行"), "dragover", data); drag(folderButton(host, "u1:1号线/上行"), "drop", data); await new Promise(resolve => setTimeout(resolve, 20)) })
    expect(posts[0]).toEqual({ action: "folder", op: "move", owner: "u1", path: "", folder: "1号线/上行", keys: ["steve:a.ogg"] })

    // A new folder in 1号线; a name already there is refused in the page
    await act(async () => folderButton(host, "u1:1号线").click())
    await act(async () => buttonNamed(host, "新建文件夹").click())
    const name = host.querySelector("input[aria-label='新文件夹名']") as HTMLInputElement
    const type = async (value: string) => act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(name, value); name.dispatchEvent(new Event("input", { bubbles: true })) })
    await type("上行")
    await act(async () => buttonNamed(host, "确定").click())
    expect(host.textContent).toContain("这里已经有同名文件夹")
    await type("下行")
    await act(async () => { buttonNamed(host, "确定").click(); await new Promise(resolve => setTimeout(resolve, 20)) })
    expect(posts[1]).toEqual({ action: "folder", op: "create", owner: "u1", path: "1号线", name: "下行" })

    // Deleting asks first
    await act(async () => buttonNamed(host, "删除文件夹").click())
    expect(posts.length).toBe(2)
    await act(async () => { buttonNamed(host, "确定").click(); await new Promise(resolve => setTimeout(resolve, 20)) })
    expect(posts[2]).toEqual({ action: "folder", op: "delete", owner: "u1", path: "1号线" })

    // Someone else's folder can't be organised by a player who isn't a manager
    await act(async () => folderButton(host, "u2:").click())
    expect(buttonNamed(host, "新建文件夹")).toBeUndefined()
  })

  it("uploads a picture into the folder chosen, the open one by default", async () => {
    const posts: any[] = []
    const library = { canUpload: true, canManage: false, busy: false, message: "", maxBytes: 5242880, maxDimension: 2048, self: "u1",
      entries: [{ hash: "a".repeat(64), name: "steve:map.png", width: 1, height: 1, size: 10, canDelete: true, uploader: "u1", uploaderName: "Steve", folder: "站名", uploadedAt: 1 }],
      folders: [{ owner: "u1", ownerName: "Steve", path: "站名/甲站" }] }
    const host = await show(ImagesLibrary, library, posts)
    await act(async () => folderButton(host, "u1:站名/甲站").click())
    expect(host.querySelectorAll("[data-entry]").length).toBe(0)
    const gif = new File([new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0, 0, 0])], "logo.gif", { type: "image/gif" })
    const input = host.querySelector("input[type=file]") as HTMLInputElement
    Object.defineProperty(input, "files", { value: [gif], configurable: true })
    await act(async () => { input.dispatchEvent(new Event("change", { bubbles: true })) })
    const target = host.querySelector("[data-upload-target]") as HTMLElement
    const select = target.querySelector("select") as HTMLSelectElement
    expect(select.value).toBe("站名/甲站")
    expect([...select.options].map(option => option.value)).toEqual(["", "站名", "站名/甲站"])
    await act(async () => { buttonNamed(target, "上传").click(); await new Promise(resolve => setTimeout(resolve, 50)) })
    expect(posts[0]).toMatchObject({ name: "logo.gif", folder: "站名/甲站" })
  })
})
