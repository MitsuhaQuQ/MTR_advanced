# state/store.tsx

新页面的全局状态（React Context）：游戏数据（像旧页面一样轮询）和草稿（带撤销/重做、自动保存、应用）。草稿保存在 `api/draft`：本页改动在最后一次改动 400 ms 后保存；每 5 s 读一次服务器上的草稿，若别处（另一个标签页里的本页面）改了且本页没有待保存的改动，就接管它并清空撤销栈（否则撤销会撤掉别处的工作）。还与 `legacy/engine.ts` 双向同步：游戏数据 `pushData`、草稿 `pushDraft`，旧代码改的草稿经 `onLegacyDraft` 作为一步编辑回来。由 `App.tsx` 用 `StoreProvider` 包住整个页面。`StoreContext` 也导出（0.69 退役第 9 步），供测试给组件一个自己的 store（`test/adhoc.test.tsx`）。

## 常量
- `POLL_MILLIS = 1500`：页面可见时轮询 `api/revision` 的间隔（毫秒）；`POLL_HIDDEN_MILLIS = 5000`：页面隐藏时的间隔。
- `DRAFT_MILLIS = 5000`：读服务器草稿的间隔。
- `SAVE_DELAY = 400`：最后一次改动后多久保存草稿。
- `UNDO_DEPTH = 100`：撤销栈最多保留的步数（多一步缓冲，见 `edit`）。

## 类型
- `History`（内部）：`{ past, now, future }`，均为 `Draft`。
- `Store`（导出）：Context 的值：
  - `model: Model | null`：由 `buildModel(data)` 得到的模型（`core/model.ts`），没有数据时为 null；
  - `connection: Connection`：连接状态（`api/client.ts`）；
  - `loadedAt`：最近一次读到新数据的时间戳（毫秒）；`language`：游戏语言；
  - `draft`：当前草稿；`draftLoaded`：是否已从服务器读到过草稿；
  - `edit(change)`：用 `change(draft) => draft` 改草稿，算一步撤销；返回同一对象则不算；
  - `undo()`、`redo()`、`canUndo`、`canRedo`；
  - `applying`：正在应用；`applyResult: ApplyResult | null`：最近一次应用结果；`boundaryErrors`：边界/车站相关的应用错误（键如边界 ID 或 `station:<ID>`，值为错误信息）；
  - `apply()`：把草稿发给游戏；`dismissResult()`：清掉应用结果；
  - `sendNow(operations)`：不经草稿直接发送操作（收回列车、调度命令），返回是否成功；
  - `refresh()`：立刻重读游戏数据。

## 导出
### `useStore()`
- 读取 Context（断言非空）。调用者：`App.tsx:25`、`shell/Banner.tsx:11`、`shell/Review.tsx`、`shell/Sync.tsx`。

### `StoreProvider({ children })`（组件）
- props：`children`。
- 游戏数据轮询（`useEffect`）：`poll()` 先 `api.revision()`，版本号变了才 `api.data()`，然后 `setLanguage`（i18n）、`setData`、`pushData(fresh)`（交给旧代码）、记下语言和时间；成功则连接为 `ok`，失败按 `connectionOf` 设状态；按页面是否隐藏安排下一次。`pollNow` 把已知版本号清空后立刻轮询。
- `save(draft)`：标记 `dirty`，防抖 `SAVE_DELAY` 后 `api.saveDraft(draft)`，成功记下 `savedJson`；失败忽略（下次改动再存）。
- 草稿读取（`useEffect`）：不 `dirty` 时每 `DRAFT_MILLIS` 读 `api.draft()`，与 `savedJson` 不同且仍不 `dirty` 时 `setHistory({past:[], now: normalizeDraft(saved), future:[]})`，并置 `draftLoaded`。
- `edit` / `undo` / `redo`：维护 `History`，每次都调用 `save`。`edit` 时 `past` 截到最后 `UNDO_DEPTH` 项再追加旧 `now`，并清空 `future`。
- `model`：`useMemo(buildModel(data))`。
- 与旧代码同步：`history.now` 变化时 `pushDraft`；注册 `onLegacyDraft(draft => edit(() => normalizeDraft(draft)))`。
- `apply()`：正在应用或无模型时不做；`draftOperations(model, draft)` 得到 `operations` 与 `owners`，无操作则返回；`api.apply(operations)`，`settleApply(draft, owners, answer)` 得到剩余草稿、结果和边界错误；合并 `boundaryErrors`，设 `applyResult`，把剩余草稿设为新历史（清空撤销），`save`。网络错误时结果为失败（密钥失效 / 游戏可能已关闭）。最后 `pollNow()`。调用者：`shell/Review.tsx`（审阅面板的应用按钮，未逐行确认）。
- `sendNow(operations)`：`api.apply` 后 `pollNow()`，返回 `true`；失败返回 `false`。调用者：`shell/Sync.tsx:53`。
- 返回：`StoreContext.Provider`。调用者：`App.tsx:19`（`App` 组件内）。
