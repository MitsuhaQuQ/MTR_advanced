// With SCHEMATIC_GALLERY=<folder>: the real drawing and the schematic of each of the six track-layout lines
// (TestKit's Lines.java, exported to fixtures/networks/lines-*.json), one line at a time, for people to look at.
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from "fs"
import { join } from "path"
import { it } from "vitest"
import { trackSchematic } from "../src/core/trackSchematic"
import { orthogonalTracks } from "../src/core/orthogonalTracks"
import { svgOf } from "./schematicQuality"

const gallery = process.env.SCHEMATIC_GALLERY
it.skipIf(!gallery)("six track-layout lines, one at a time", () => {
  const folder = join(__dirname, "fixtures", "networks")
  const file = readdirSync(folder).filter(f => f.startsWith("lines-")).sort().pop()!
  const net = JSON.parse(readFileSync(join(folder, file), "utf8"))
  mkdirSync(gallery!, { recursive: true })
  for (let line = 0; line < 6; line++) {
    const z0 = line * 300, inLine = (k: number) => net.nodes[k][1] > z0 - 40 && net.nodes[k][1] < z0 + 60
    const rails = net.rails.filter((r: any) => inLine(r[0]) && inLine(r[1]))
    const stations = (net.stations || []).filter((s: any) => s.z > z0 - 40 && s.z < z0 + 60)
    const part = { ...net, rails, stations, routes: (net.routes || []).filter((r: any) => r.nodes.some(inLine)) }
    const real = trackSchematic(part, net.platforms || []), schematic = orthogonalTracks(real)
    writeFileSync(join(gallery!, `line${line + 1}-real.svg`), svgOf(real, `${line + 1}号线 实际图`))
    writeFileSync(join(gallery!, `line${line + 1}-schematic.svg`), svgOf(schematic, `${line + 1}号线 示意图`))
  }
})
