// The schematic on real networks exported from the game (test/fixtures/networks, by autotest/tools/export-network.mjs):
// tracks side by side in the real drawing stay side by side, no crossings appear that are not there, every segment is
// horizontal, vertical or 45 degrees. With SCHEMATIC_GALLERY=<folder>, the real drawing and the schematic of each network
// are written there as SVG, problem rails in red.
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from "fs"
import { join } from "path"
import { describe, expect, it } from "vitest"
import { trackSchematic } from "../src/core/trackSchematic"
import { orthogonalTracks } from "../src/core/orthogonalTracks"
import { measure, svgOf } from "./schematicQuality"
import { qNetwork, qPlatforms } from "./fixtures/qNetwork"

const folder = join(__dirname, "fixtures", "networks")
const networks: { name: string; net: any; platforms: any[] }[] = [
  { name: "q-2026-11-10", net: qNetwork, platforms: qPlatforms },
  ...readdirSync(folder).filter(f => f.endsWith(".json")).map(f => {
    const sample = JSON.parse(readFileSync(join(folder, f), "utf8"))
    return { name: f.replace(/\.json$/, ""), net: sample, platforms: sample.platforms || [] }
  }),
]

// What each network has now, so a change can only make it better; lower these as the layout improves
const LIMITS: Record<string, { apart: number; newCrossings: number; steps: number }> = {
  // Before the schematic followed the real drawing (2026-11-12): apart 0 and 11, new crossings 3 and 26, steps 21 and 26
  "q-2026-11-10": { apart: 1, newCrossings: 8, steps: 3 },
  "q-2026-11-12": { apart: 13, newCrossings: 9, steps: 2 },
}

describe("schematic quality on real networks", () => {
  for (const { name, net, platforms } of networks) {
    it(name, () => {
      const real = trackSchematic(net, platforms)
      const schematic = orthogonalTracks(real)
      const quality = measure(real, schematic)
      console.log(`${name}: ${real.rails.length} rails, ${quality.besidePairs} beside others, ${quality.apart.length} drawn apart, ${quality.newCrossings.length} new crossings, ${quality.offAngle} off-angle segments, ${quality.broken.length} broken, ${quality.steps} steps`)
      const gallery = process.env.SCHEMATIC_GALLERY
      if (gallery) {
        mkdirSync(gallery, { recursive: true })
        const marked = new Set([...quality.apart.map(p => p.rail), ...quality.newCrossings.flat()])
        writeFileSync(join(gallery, `${name}-real.svg`), svgOf(real, `${name} 实际图`, marked))
        writeFileSync(join(gallery, `${name}-schematic.svg`), svgOf(schematic, `${name} 示意图：并排被拉开 ${quality.apart.length}，新增交叉 ${quality.newCrossings.length}，台阶 ${quality.steps}`, marked))
        writeFileSync(join(gallery, `${name}.json`), JSON.stringify(quality, null, 1))
      }
      const limit = LIMITS[name] || { apart: 0, newCrossings: 0, steps: 0 }
      expect(quality.broken, "rails with points that are not numbers").toEqual([])
      expect(quality.offAngle).toBe(0)
      expect(quality.apart.length).toBeLessThanOrEqual(limit.apart)
      expect(quality.newCrossings.length).toBeLessThanOrEqual(limit.newCrossings)
      expect(quality.steps, "little right-angle steps").toBeLessThanOrEqual(limit.steps)
    })
  }
})
