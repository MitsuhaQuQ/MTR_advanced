# shell/icons.tsx

新页面的小线条图标，直接写 SVG 路径而不引入图标包，以保持脚本体积小。被 `App.tsx`（导轨和"回到旧版"按钮）和 `shell/layers.ts`（类型）使用。

## 内部
- `paths`：图标名 → SVG `path` 的 `d` 字符串（24×24 视框）。有 `track`、`owners`、`lines`、`timetable`、`depots`、`dispatch`、`media`、`back`。

## 导出
- `type IconName`：`paths` 的键。使用者：`shell/layers.ts:1`。
- `Icon({ name, size = 18 })`（组件）：props `name` 图标名，`size` 像素边长。返回描边为 `currentColor`、线宽 1.8 的 `svg`，`aria-hidden`。调用者：`App.tsx:111`、`App.tsx:144`。
