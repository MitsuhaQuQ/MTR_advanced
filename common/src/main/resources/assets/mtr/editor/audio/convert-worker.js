/* Encode speech in a dedicated classic worker; all scripts are bundled and served from the editor itself. */
self.OggVorbisEncoderConfig = { TOTAL_MEMORY: 32 * 1024 * 1024 }
importScripts("OggVorbisEncoder.js")
self.onmessage = ({ data: { samples, sampleRate } }) => {
  let encoder
  try {
    encoder = new self.OggVorbisEncoder(sampleRate, 1, 0.3)
    for (let offset = 0; offset < samples.length; offset += 16384) {
      encoder.encode([samples.subarray(offset, offset + 16384)])
      self.postMessage({ progress: Math.round(Math.min(offset + 16384, samples.length) * 100 / samples.length) })
    }
    const blob = encoder.finish()
    encoder = null
    self.postMessage({ blob })
  } catch {
    if (encoder) encoder.cancel()
    self.postMessage({ error: true })
  }
}
