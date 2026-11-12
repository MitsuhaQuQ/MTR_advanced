# shell/Banner.tsx

顶栏下方的提示条（对应旧页面 `renderBanner`）：连接问题、没有编辑权限、草稿之后游戏里被改过的线路（冲突）、应用的结果。读 `state/store.tsx` 的 `model`、`draft`、`applyResult`、`connection`；冲突用 `core/draft.ts` 的 `resolveConflict` 处理。由 `App.tsx` 放在顶栏下。

## 导出
### `Banner({ onReview })`（组件）
- props：`onReview` 打开审阅面板（"看草稿"按钮）。
- 内部：
  - `name(id)`：路线 ID → `routeTitle`，路线不存在时"已删除的线路"。
  - `failed`：草稿中带 `error` 或 `conflict` 的路线条目；`otherFailed`：运用（`draft.duties`）或新建种别（`draft.created`）有错误。
  - `connectionText`：按 `store.connection`：`key` 链接失效、`world` 不在世界里、`offline` 连不上游戏；`connecting`/`ok` 为空。
  - `conflictList(entries)`：列出路线及错误/冲突说明；冲突项给两个按钮：`store.edit(d => resolveConflict(model, d, id, true))`（以最新版本为基础覆盖对方）和 `false`（放弃草稿用游戏里的）。
- 显示优先级：连接问题 → 无编辑权限 → 未应用时存在冲突 → 应用后有失败（应用了多少项、通用错误、运用错误、新种别错误、失败的路线）→ 应用成功（"已应用 N 项"）。都没有则返回 `null`。
- 有应用结果时右侧显示"看草稿"（警告时）和关闭（`store.dismissResult`）。
- 副作用：`store.edit`（改草稿）、`store.dismissResult`。
- 调用者：`App.tsx:62`。
