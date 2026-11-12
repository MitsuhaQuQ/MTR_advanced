# shell/Review.tsx

应用前的"草稿的改动"面板（对应旧页面 `draftChangesDialog`）：按部分和游戏当前版本比较，每部分可放弃；冲突有两个处理方式；撤销、重做、清空全部、应用。改动内容由 `core/draft.ts` 的 `changeSections` 生成。由 `App.tsx` 打开（顶栏按钮、提示条、Ctrl+Enter）。

## 导出
### `ReviewPanel({ open, onClose, onOpenRoute })`（组件）
- props：`open` 是否显示；`onClose` 关闭；`onOpenRoute(routeId)` 可选，跳到该路线的汇总时刻表（`App.tsx` 的 `openRoute`）。
- 计算：`draftCount`、`conflictCount`、`changeSections(model, draft, store.boundaryErrors)`；可应用条件同顶栏（未在应用、草稿数 > 冲突数、有编辑权限）。
- 底部按钮：撤销/重做（`store.undo/redo`）；`ConfirmButton` 清空全部草稿（`store.edit(() => emptyDraft())`）；应用（`await store.apply()`，会发 `api/apply`）。
- 内容：没有草稿时提示；每个部分一个 `Section`；有草稿时显示快捷键说明。
- 调用者：`App.tsx:74`。

## 内部组件
### `Section({ section, onClose, onOpenRoute })`
- props：`section: ChangeSection`（`core/draft.ts`：`key`、`title`、`error?`、`conflict?`、`route?`、`items`（字符串或 `{title, lines}`）、`drop?`）；`onClose`；`onOpenRoute`。
- 显示标题、错误、冲突处理按钮（`resolveConflict(..., true/false)`）、改动条目（可两级），以及"跳到这里"（先关面板再 `onOpenRoute`）和"放弃这条/这项的草稿"（`store.edit(d => drop(d, section.drop))`）。
- 副作用：`store.edit`。
- 调用者：本文件 `ReviewPanel`。
