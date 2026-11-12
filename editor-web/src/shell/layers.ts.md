# shell/layers.ts

新页面的工作层清单。页面按存档的分层组织（`MTR-网页编辑器规格.md` 第 3 节）：物理配线、逻辑归属、运营线路、时刻表与交路、车厂；然后是在线调度台和媒体库。被 `App.tsx`（左侧导轨、层提示、地址栏）使用。

## 类型
- `LayerId`：`"physical" | "owners" | "lines" | "timetable" | "depots" | "dispatch" | "media"`。
- `LayerInfo`：`id`；`name` 导轨上的短名（中文，经 `tr` 显示）；`hint` 层说明；`icon` 图标名（`shell/icons.tsx`）；`step?` 第几层（调度、媒体没有）；`movesIn` 迁移计划中该层移入新页面的步骤号；`old` 旧页面做同样工作的地址（迁移完成前）。`movesIn` 与 `old` 在 `src` 中未见读取处。

## 导出
- `LAYERS: LayerInfo[]`：七层：配线（1）、归属（2）、线路（3）、时刻（4）、车厂（5）、调度、媒体（旧地址 `../#/media/audio`）。调用者：`App.tsx:58`、`App.tsx:136`。
- `layerOf(hash)`：从 `location.hash`（如 `#/lines/...`）取第一段作为层 ID；不认识时返回 `"timetable"`。调用者：`App.tsx:26`、`App.tsx:29`。
