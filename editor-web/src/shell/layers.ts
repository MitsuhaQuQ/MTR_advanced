import type { IconName } from "./icons"

/**
 * The page is organised as the save is layered (MTR-网页编辑器规格.md, section 3): physical track, logical ownership,
 * operating lines, timetables and routes, depots; then the live dispatcher's console and the media library.
 * `old` is where the same work is done on the old page until the layer is moved here (steps 2–6 of the migration plan).
 */
export type LayerId = "physical" | "owners" | "lines" | "timetable" | "depots" | "dispatch" | "media"

export type LayerInfo = { id: LayerId; name: string; hint: string; icon: IconName; step?: number; movesIn: number; old: string }

export const LAYERS: LayerInfo[] = [
  { id: "physical", name: "配线", hint: "轨道、区段、寻路边界、留置位", icon: "track", step: 1, movesIn: 4, old: "../#/timetable" },
  { id: "owners", name: "归属", hint: "逻辑线路和联络线", icon: "owners", step: 2, movesIn: 4, old: "../#/timetable" },
  { id: "lines", name: "线路", hint: "运营线路、种别、停站", icon: "lines", step: 3, movesIn: 5, old: "../#/timetable" },
  { id: "timetable", name: "时刻", hint: "时刻表、运行图、交路", icon: "timetable", step: 4, movesIn: 2, old: "../#/timetable" },
  { id: "depots", name: "车厂", hint: "运用、存车线、车池", icon: "depots", step: 5, movesIn: 3, old: "../#/timetable" },
  { id: "dispatch", name: "调度", hint: "在线列车和调度命令", icon: "dispatch", movesIn: 6, old: "../#/timetable" },
  { id: "media", name: "媒体", hint: "报站音频等媒体文件", icon: "media", movesIn: 6, old: "../#/media/audio" },
]

export const layerOf = (hash: string): LayerId => {
  const id = hash.replace(/^#\/?/, "").split("/")[0]
  return (LAYERS.find(l => l.id === id)?.id) ?? "timetable"
}
