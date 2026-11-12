# legacy/stage.ts

新页面中旧元素的"后台舞台"。在旧代码运行之前（`legacy/engine.ts` 第一行就 `import "./stage"`），用 [`markup.ts`](markup.ts.md) 里的 `LEGACY_MARKUP`（旧代码仍按 ID 查找的那些元素；旧页面 `index.html` 已在退役第 9 步删除）一次性造出这些元素，放进一个隐藏的 `div`，这样旧代码按 ID 查找元素和挂事件都能找到。新页面布局需要显示某个旧元素时用 `LegacySlot`（[Slot.tsx](Slot.tsx.md)）"借走"，不显示时再放回来。

提示框 `#tooltip` 放进始终显示的 `overlays` 容器（旧的 `#dialog` 已随旧对话框删除）。所有承载旧元素的容器都有类名 `legacy`，旧页面样式（`public/legacy.css`）只作用于它之下。

## 模块加载时的副作用
- 创建 `stage`（`class="legacy"`、`hidden`），`innerHTML = LEGACY_MARKUP`，追加到 `document.body`。
- 创建 `overlays`（`class="legacy legacy-overlays"`），追加到 `body`，并把 `#tooltip` 移入。
- 对两者调用 `applyStatic`（`i18n.ts`），翻译静态文字。

## 导出
- `stage: HTMLDivElement`：隐藏舞台。本文件 `returnToStage` 使用。
- `overlays: HTMLDivElement`：始终显示的覆盖层容器。本文件内使用，外部未见引用。
- `legacyElement(id)`：按 ID 取旧元素（即 `document.getElementById`），`markup.ts` 里的每个 ID 都存在。返回 `HTMLElement | null`。调用者：`legacy/Slot.tsx:14`、`views/Inspector.tsx:20`、`views/Inspector.tsx:50`、`views/Navigator.tsx:19`。
- `returnToStage(element)`：把被布局借走的元素放回舞台。副作用：移动 DOM 节点。调用者：`legacy/Slot.tsx:18`。
