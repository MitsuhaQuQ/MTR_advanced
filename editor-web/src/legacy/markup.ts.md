# legacy/markup.ts

剩余旧代码（[oldEditor.js](oldEditor.js.md)）仍按 ID 查找的旧元素的标记。退役第 9 步删除旧页面时取代了原来由生成器从旧 `index.html` 切出的 `markup.generated.ts`，只留下还用得到的：

- `#tree`：导航栏（新页面 `views/Navigator.tsx` 用 portal 画进去，旧代码在上面挂点击、右键、键盘处理）；
- `#inspector`：检查栏（`views/Inspector.tsx` 画进去，旧代码在上面挂运用编辑、选站等表单的处理）；
- `#canvas-wrap`：运行图的盒子（不再有画布；新运行图叠在上面，旧代码按它的大小算布局、居中、缩放）；
- `#sim-status`：模拟状态文字（`renderSimStatus` 写入，`views/Layers.tsx` 借去显示）；
- `#view-network`：全网实际图（"轨道编辑"）及其工具栏 `#net-mode`、`#net-layer`、`#net-fit`、`#net-zoom-out`、`#net-zoom-in`、`#net-refresh`、`#net-console-toggle`、`#net-info`、`#net-legend`，画布 `#net-canvas`，出入库选路面板 `#net-waypick`；
- `#tooltip`：提示框（`stage.ts` 移到始终显示的覆盖层）。

带 `data-i18n*` 的文字由 `stage.ts` 的 `applyStatic` 翻译。

## 导出
- `LEGACY_MARKUP: string`。调用者：`legacy/stage.ts`。
