// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from "vitest"
import { act } from "react"
import { createRoot, type Root } from "react-dom/client"
import { ImagesLibrary, prepareImage } from "../src/views/ImagesLibrary"

let root: Root | undefined
afterEach(async () => { if (root) await act(async () => root?.unmount()); root = undefined; document.body.innerHTML = ""; vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe("image imports", () => {
  it("rejects URLs and oversized source files", async () => {
    await expect(prepareImage(new File(["x"], "https://example.com/image.svg"), 2048, 5242880)).rejects.toThrow()
    await expect(prepareImage({ name: "image.png", size: 17 * 1024 * 1024 } as File, 2048, 5242880)).rejects.toThrow("16 MB")
  })
  it("rejects oversized dimensions and releases the decoded bitmap", async () => {
    const close = vi.fn()
    vi.stubGlobal("createImageBitmap", async () => ({ width: 2049, height: 1, close }))
    await expect(prepareImage(new File(["x"], "image.jpg"), 2048, 5242880)).rejects.toThrow("2048")
    expect(close).toHaveBeenCalledOnce()
  })
  it("normalizes JPEG to PNG and checks the resulting upload size", async () => {
    const close = vi.fn(), drawImage = vi.fn()
    vi.stubGlobal("createImageBitmap", async () => ({ width: 10, height: 20, close }))
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(callback => callback(new Blob(["png"], { type: "image/png" })))
    const upload = await prepareImage(new File(["jpeg"], "image.jpg"), 2048, 100)
    expect(upload.name).toBe("image.png")
    expect(upload.blob.type).toBe("image/png")
    await expect(prepareImage(new File(["jpeg"], "image.jpg"), 2048, 1)).rejects.toThrow("5 MB")
    expect(close).toHaveBeenCalledTimes(2)
  })
  it("uploads a PNG that fits as it is, and scales down one too big once saved as PNG", async () => {
    const close = vi.fn(), drawImage = vi.fn()
    vi.stubGlobal("createImageBitmap", async () => ({ width: 2000, height: 1000, close }))
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D)
    // A PNG of a photo: 4 bytes a pixel, so 2000×1000 is 8 MB
    vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(function (this: HTMLCanvasElement, callback) { callback(new Blob([new Uint8Array(this.width * this.height * 4)], { type: "image/png" })) })
    const png = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3])], "map.PNG", { type: "image/png" })
    expect(await prepareImage(png, 2048, 5242880)).toEqual({ name: "map.png", blob: png })
    expect(drawImage).not.toHaveBeenCalled()
    // A 3.6 MB photo (Q world, 2026-11-12): over 5 MB as PNG, so scaled to fit
    const photo = await prepareImage(new File(["jpeg"], "photo.jpg"), 2048, 5242880)
    expect(photo.blob.size).toBeLessThanOrEqual(5242880)
    expect(photo.scaled![0] / photo.scaled![1]).toBeCloseTo(2, 1)
    expect(photo.scaled![0]).toBeGreaterThan(1000)
    expect(close).toHaveBeenCalledTimes(2)
  })
  it("uploads a JPEG as JPEG: as it is when it fits, saved again as JPEG when not", async () => {
    const close = vi.fn(), drawImage = vi.fn()
    vi.stubGlobal("createImageBitmap", async () => ({ width: 2000, height: 1000, close }))
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D)
    const toBlob = vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(callback => callback(new Blob([new Uint8Array(1000)], { type: "image/jpeg" })))
    const head = [0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46]
    const small = new File([new Uint8Array(head)], "photo.JPEG", { type: "image/jpeg" })
    expect(await prepareImage(small, 2048, 5242880)).toEqual({ name: "photo.jpg", blob: small })
    expect(toBlob).not.toHaveBeenCalled()
    const big = new File([new Uint8Array([...head, ...new Uint8Array(6000)])], "photo.jpg", { type: "image/jpeg" })
    const upload = await prepareImage(big, 2048, 5000)
    expect(upload.name).toBe("photo.jpg")
    expect(upload.scaled).toBeUndefined()
    expect(toBlob.mock.calls[0].slice(1)).toEqual(["image/jpeg", 0.9])
  })
  it("uploads the original animated GIF bytes", async () => {
    const bytes = new Uint8Array(Buffer.from("47494638396102000100810000000000ff000000ff000000ff21ff0b4e45545343415045322e30030101000021f90405050000002c00000000020001000002024c0a0021f9040d070000002c010000000100010000020254010021f90409030000002c00000000010001000002025c010021f904050a0000002c01000000010001000002025401003b", "hex"))
    const gif = new File([bytes], "animation.GIF", { type: "image/gif" })
    expect(await prepareImage(gif, 2048, 5242880)).toEqual({ name: "animation.gif", blob: gif })
    await expect(prepareImage(gif, 1, 5242880)).rejects.toThrow("1")
    await expect(prepareImage(gif, 2048, 1)).rejects.toThrow("5 MB")
  })
  for (const administrator of [false, true]) it(`${administrator ? "administrator" : "ordinary user"} gets the server's delete action for another user's picture`, async () => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true)
    vi.stubGlobal("fetch", async (url: string) => url.includes("image-file") ? new Response("png") : new Response(JSON.stringify({ canUpload: false, busy: false, message: "", maxBytes: 5242880, maxDimension: 2048, entries: [{ hash: "a".repeat(64), name: "alex:map.png", width: 1, height: 1, size: 100, uploaderName: "Alex", uploadedAt: 0, canDelete: administrator }] })))
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:test")
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {})
    const host = document.createElement("div"); document.body.append(host); root = createRoot(host)
    await act(async () => { root?.render(<ImagesLibrary />) })
    const item = [...host.querySelectorAll("button")].find(button => button.textContent?.includes("alex:map.png"))!
    await act(async () => { item.click() })
    const deletion = [...host.querySelectorAll("button")].find(button => button.textContent === "删除")
    expect(Boolean(deletion)).toBe(administrator)
  })
})
