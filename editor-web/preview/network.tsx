import React from "react"
import { createRoot } from "react-dom/client"
import { trackSchematic } from "../src/core/trackSchematic"
import { TrackMapDrawing } from "../src/views/TrackMapDrawing"
import { qNetwork, qPlatforms } from "../test/fixtures/qNetwork"
import { orthogonalTracks } from "../src/core/orthogonalTracks"

const real = trackSchematic(qNetwork, qPlatforms), simple = orthogonalTracks(real)
function Preview() {
  const [shape, setShape] = React.useState("schematic")
  const map = shape === "real" ? real : simple
  return <main style={{ background: "#f4f7f6", color: "#233537", height: "100vh", "--bg": "#f4f7f6", "--panel": "white", "--ink": "#233537", "--muted": "#526768", "--grid-strong": "#657d7c", "--sel": "#2677c9" } as React.CSSProperties}>
  <div style={{ padding: "10px 20px", fontFamily: "sans-serif" }}><button onClick={() => setShape("schematic")}>示意图</button> <button onClick={() => setShape("real")}>实际图</button> Q 世界配线预览 · 96 条真实轨道 · 当前 {shape === "real" ? "实际曲线" : "简化折线"}</div>
  <svg style={{ width: "100%", height: "calc(100% - 42px)" }} viewBox={`0 0 ${map.width} ${map.height}`}><TrackMapDrawing map={map} zoom={Math.min(innerWidth / map.width, (innerHeight - 42) / map.height)} chosen={null} onChoose={() => {}} /></svg>
</main>
}
createRoot(document.getElementById("root")!).render(<Preview />)
