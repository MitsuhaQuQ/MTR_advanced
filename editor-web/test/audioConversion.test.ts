import { describe, expect, it } from "vitest"
import fs from "node:fs"
import vm from "node:vm"
import { convertAudio, isWav, isMp3, oggName, wavDuration } from "../src/core/audioConversion"

function wav(seconds = 1, channels = 1) {
  const samples = seconds * 8000, bytes = new ArrayBuffer(44 + samples * channels * 2), view = new DataView(bytes)
  const text = (at: number, value: string) => [...value].forEach((c, i) => view.setUint8(at + i, c.charCodeAt(0)))
  text(0, "RIFF"); view.setUint32(4, bytes.byteLength - 8, true); text(8, "WAVE"); text(12, "fmt ")
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, channels, true)
  view.setUint32(24, 8000, true); view.setUint32(28, 8000 * channels * 2, true)
  view.setUint16(32, channels * 2, true); view.setUint16(34, 16, true)
  text(36, "data"); view.setUint32(40, bytes.byteLength - 44, true)
  return bytes
}

describe("WAV conversion", () => {
  it("recognizes WAV input and replaces the extension before sound ID and update matching", () => {
    expect(isWav({ name: "next.WAV", type: "" })).toBe(true)
    expect(isWav({ name: "next", type: "audio/x-wav" })).toBe(true)
    expect(oggName("Next Station.WAV")).toBe("Next Station.ogg")
    expect(isMp3({ name: "next.MP3", type: "" })).toBe(true)
    expect(isMp3({ name: "next", type: "audio/mpeg" })).toBe(true)
    expect(isMp3({ name: "next.wav", type: "audio/wav" })).toBe(false)
    expect(oggName("Next Station.MP3")).toBe("Next Station.ogg")
  })
  it("measures mono and stereo duration from sample frames, including exactly five minutes", () => {
    expect(wavDuration(wav(300))).toBe(300)
    expect(wavDuration(wav(2, 2))).toBe(2)
  })
  it("rejects truncated and forged chunks before browser decoding", () => {
    expect(() => wavDuration(wav().slice(0, 40))).toThrow()
    const bytes = wav()
    new DataView(bytes).setUint32(40, 0xffffffff, true)
    expect(() => wavDuration(bytes)).toThrow()
    expect(() => wavDuration(new ArrayBuffer(12))).toThrow()
  })
  it("rejects server duration and source-size violations before decoding or starting a worker", async () => {
    await expect(convertAudio(new File([wav(301)], "long.wav"), 300, 2097152, () => {})).rejects.toThrow("300")
    await expect(convertAudio({ size: 128 * 1024 * 1024 + 1 } as File, 300, 2097152, () => {})).rejects.toThrow("128 MB")
    const controller = new AbortController()
    controller.abort()
    await expect(convertAudio(new File([wav()], "cancel.wav"), 300, 2097152, () => {}, controller.signal)).rejects.toThrow()
  })
  it("the bundled encoder writes real Ogg Vorbis with an end page and sample duration", async () => {
    const scope: Record<string, any> = { Blob, console, setTimeout, clearTimeout, importScripts() {} }
    scope.self = scope
    vm.runInNewContext(fs.readFileSync(new URL("../public/audio/OggVorbisEncoder.js", import.meta.url), "utf8"), scope)
    const encoder = new scope.OggVorbisEncoder(24000, 1, 0.3)
    const samples = new Float32Array(24000)
    samples.forEach((_, i) => { samples[i] = Math.sin(i * Math.PI * 2 * 440 / 24000) * 0.2 })
    encoder.encode([samples])
    const bytes = new Uint8Array(await encoder.finish().arrayBuffer())
    expect(new TextDecoder().decode(bytes.subarray(0, 4))).toBe("OggS")
    expect(new TextDecoder().decode(bytes).includes("vorbis")).toBe(true)
    expect(bytes[39]).toBe(1)
    let end = false
    for (let at = 0; at < bytes.length;) {
      const segments = bytes[at + 26]
      const body = bytes.subarray(at + 27, at + 27 + segments).reduce((a, b) => a + b, 0)
      if (bytes[at + 5] & 4) {
        end = true
        expect(new DataView(bytes.buffer).getUint32(at + 6, true)).toBe(24000)
      }
      at += 27 + segments + body
    }
    expect(end).toBe(true)
  })
})
