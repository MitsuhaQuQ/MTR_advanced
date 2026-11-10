import { tr } from "../i18n"

const MAX_SOURCE_BYTES = 128 * 1024 * 1024
const SAMPLE_RATE = 24000
export const isWav = (file: { name: string; type: string }) => /\.wav$/i.test(file.name) || /^(audio\/(x-)?wav|audio\/wave)$/i.test(file.type)
export const isMp3 = (file: { name: string; type: string }) => /\.mp3$/i.test(file.name) || /^audio\/(mpeg|mp3|x-mp3)$/i.test(file.type)
export const oggName = (name: string) => name.replace(/\.(wav|mp3)$/i, "") + ".ogg"

/** Read compressed audio duration before allocating its decoded PCM buffer. */
async function mp3Duration(file: File, signal?: AbortSignal): Promise<number> {
  signal?.throwIfAborted()
  const audio = document.createElement("audio"), url = URL.createObjectURL(file)
  try {
    return await new Promise((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); signal?.removeEventListener("abort", abort); audio.onloadedmetadata = null; audio.onerror = null }
      const fail = (error: Error) => { cleanup(); reject(error) }
      const abort = () => fail(new DOMException("Aborted", "AbortError"))
      const timer = window.setTimeout(() => fail(new Error(tr("无法读取 MP3，请检查文件是否损坏或浏览器是否支持。"))), 20000)
      audio.onloadedmetadata = () => {
        if (!Number.isFinite(audio.duration) || audio.duration <= 0) { fail(new Error(tr("无法读取 MP3，请检查文件是否损坏或浏览器是否支持。"))); return }
        cleanup()
        resolve(audio.duration)
      }
      audio.onerror = () => fail(new Error(tr("无法读取 MP3，请检查文件是否损坏或浏览器是否支持。")))
      signal?.addEventListener("abort", abort, { once: true })
      audio.preload = "metadata"
      audio.src = url
    })
  } finally { audio.removeAttribute("src"); audio.load(); URL.revokeObjectURL(url) }
}

/** Read the RIFF metadata before the browser allocates a decoded audio buffer. */
export function wavDuration(bytes: ArrayBuffer): number {
  const view = new DataView(bytes)
  const text = (offset: number, length: number) => String.fromCharCode(...new Uint8Array(bytes, offset, length))
  const invalid = () => new Error(tr("无法读取 WAV，请使用 PCM 或浮点 WAV 文件。"))
  if (bytes.byteLength < 12 || text(0, 4) !== "RIFF" || text(8, 4) !== "WAVE") throw invalid()
  const end = view.getUint32(4, true) + 8
  if (end > bytes.byteLength || end < 12) throw invalid()
  let rate = 0, alignment = 0, dataSize = 0
  for (let offset = 12; offset + 8 <= end;) {
    const name = text(offset, 4), size = view.getUint32(offset + 4, true), start = offset + 8
    if (start + size > end) throw invalid()
    if (name === "fmt ") {
      if (size < 16) throw invalid()
      const format = view.getUint16(start, true), channels = view.getUint16(start + 2, true)
      rate = view.getUint32(start + 4, true)
      alignment = view.getUint16(start + 12, true)
      if (![1, 3, 0xfffe].includes(format) || channels < 1 || channels > 2 || rate < 8000 || rate > 192000 || alignment < channels) throw invalid()
    } else if (name === "data") dataSize += size
    offset = start + size + (size & 1)
  }
  if (!rate || !alignment || !dataSize || dataSize % alignment) throw invalid()
  return dataSize / alignment / rate
}

/** Convert locally; only the resulting Vorbis file goes through the existing upload endpoint. */
export async function convertAudio(file: File, maxSeconds: number, maxBytes: number, progress: (percent: number) => void, signal?: AbortSignal): Promise<File> {
  if (file.size > MAX_SOURCE_BYTES) throw new Error(tr("音频源文件不能超过 128 MB。"))
  signal?.throwIfAborted()
  const mp3 = isMp3(file)
  const metadataDuration = mp3 ? await mp3Duration(file, signal) : null
  if (metadataDuration !== null && metadataDuration > maxSeconds) throw new Error(tr("音频不能超过 {0} 秒", maxSeconds))
  if (metadataDuration !== null && metadataDuration > 600) throw new Error(tr("网页转换最多支持 10 分钟的音频。"))
  const bytes = await file.arrayBuffer()
  const duration = metadataDuration ?? wavDuration(bytes)
  if (duration > maxSeconds) throw new Error(tr("音频不能超过 {0} 秒", maxSeconds))
  // Bound decoded memory even when an administrator permits longer recordings.
  if (duration > 600) throw new Error(tr("网页转换最多支持 10 分钟的音频。"))
  progress(0)
  let audio: AudioBuffer
  try {
    // Offline rendering resamples and downmixes speech without playing it or requesting microphone access.
    const decoded = await new OfflineAudioContext(1, 1, SAMPLE_RATE).decodeAudioData(bytes)
    signal?.throwIfAborted()
    if (decoded.duration > maxSeconds) throw new Error(tr("音频不能超过 {0} 秒", maxSeconds))
    if (decoded.duration > 600) throw new Error(tr("网页转换最多支持 10 分钟的音频。"))
    const context = new OfflineAudioContext(1, Math.ceil(decoded.duration * SAMPLE_RATE), SAMPLE_RATE)
    const source = context.createBufferSource()
    source.buffer = decoded
    source.connect(context.destination)
    source.start()
    audio = await context.startRendering()
  } catch (e) {
    if (signal?.aborted) throw e
    if (e instanceof Error && e.message === tr("音频不能超过 {0} 秒", maxSeconds)) throw e
    if (e instanceof Error && e.message === tr("网页转换最多支持 10 分钟的音频。")) throw e
    if (mp3) throw new Error(tr("无法读取 MP3，请检查文件是否损坏或浏览器是否支持。"))
    throw new Error(tr("无法读取 WAV，请使用 PCM 或浮点 WAV 文件。"))
  }
  signal?.throwIfAborted()
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("audio/convert-worker.js", new URL(import.meta.env.BASE_URL, location.href)))
    const finish = () => { clearTimeout(timer); signal?.removeEventListener("abort", abort); worker.terminate() }
    const abort = () => { finish(); reject(new DOMException("Aborted", "AbortError")) }
    const timer = window.setTimeout(() => { finish(); reject(new Error(tr("音频转换超时，请重试或使用较短的文件。"))) }, 180000)
    signal?.addEventListener("abort", abort, { once: true })
    worker.onerror = () => { finish(); reject(new Error(tr("音频转换失败，请重试或先转换为 Ogg Vorbis。"))) }
    worker.onmessage = ({ data }) => {
      if (data.error) { finish(); reject(new Error(tr("音频转换失败，请重试或先转换为 Ogg Vorbis。"))) }
      else if (data.blob) {
        finish()
        if (data.blob.size > maxBytes) reject(new Error(tr("转换后的文件超过服务器大小限制，请缩短音频。")))
        else resolve(new File([data.blob], oggName(file.name), { type: "audio/ogg" }))
      } else progress(data.progress)
    }
    const samples = audio.getChannelData(0)
    worker.postMessage({ samples, sampleRate: SAMPLE_RATE }, [samples.buffer])
  })
}
