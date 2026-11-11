import { describe, expect, it } from "vitest"
import { trackSchematic, type Point } from "../src/core/trackSchematic"
import { orthogonalTracks, elbow } from "../src/core/orthogonalTracks"
import { qNetwork, qPlatforms } from "./fixtures/qNetwork"

describe("schematic wiring layout", () => {
  it("moves nodes, preserves all 96 edges and shared endpoints, and uses only horizontal/vertical/45-degree segments", () => {
    const real=trackSchematic(qNetwork,qPlatforms),before=JSON.stringify(real),simple=orthogonalTracks(real)
    const nodes=new Map<number,Point>()
    expect(simple.rails.length).toBe(96)
    simple.rails.forEach((r,i)=>{
      expect(r.nodes).toEqual(real.rails[i].nodes)
      expect(r.kind).toBe(real.rails[i].kind)
      expect(r.station).toBe(real.rails[i].station)
      for(const [n,p] of [[r.nodes[0],r.points[0]],[r.nodes[1],r.points.at(-1)!]] as const) {
        if(nodes.has(n)) expect(p).toEqual(nodes.get(n))
        nodes.set(n,p)
      }
      r.points.slice(1).forEach((p,j)=>{
        const dx=Math.abs(p[0]-r.points[j][0]),dy=Math.abs(p[1]-r.points[j][1])
        expect(Math.min(dx,dy,Math.abs(dx-dy)), "rail " + i + " kind " + r.kind).toBeLessThan(1e-6)
      })
    })
    expect(JSON.stringify(real)).toBe(before)
    expect(simple.rails.some((r,i)=>JSON.stringify(r.points[0])!==JSON.stringify(real.rails[i].points[0]))).toBe(true)
    expect(simple.stations.map(s=>s.id)).toEqual(real.stations.map(s=>s.id))
    expect(simple.ends.length).toBe(real.ends.length)
    expect(simple.junctions.length).toBe(real.junctions.length)
  })
  it("uses a short chamfer at an L bend and handles all quadrants",()=>{
    for(const b of [[200,400],[-200,400],[200,-400],[-200,-400]] as Point[]) {
      const p=elbow([0,0],b,true)
      expect(p[1][0]).toBe(0)
      expect(p.at(-1)).toEqual(b)
      expect(Math.abs(p[2][0]-p[1][0])).toBe(32)
    }
    expect(orthogonalTracks(trackSchematic({nodes:[],rails:[]})).rails).toEqual([])
  })
})
