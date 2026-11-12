# ui/bits.tsx

新页面各面板共用的小组件：标签、右侧滑出面板、两步确认按钮、键帽。

## 导出
### `Pill({ tone, children, title })`
- props：`tone` 色调 `"warn" | "caution" | "sel" | "accent" | "plain"`；`children` 文本；`title` 悬停提示（可选）。
- 返回圆角小标签 `span`。调用者：`views/StopsEditor.tsx:99`、`views/StopsEditor.tsx:266` 等。

### `SidePanel({ open, onClose, title, hint, children, footer, width = 460 })`
- 从右侧滑入、盖在页面上的面板，用于阅读和操作列表（草稿改动、同步状态、各问答面板）。
- props：`open` 是否显示；`onClose` 关闭回调；`title` 标题；`hint` 标题下说明（可选）；`children` 内容（可滚动）；`footer` 底部按钮区（可选）；`width` 最大宽度（像素）。
- 副作用：打开时把焦点移入面板，捕获阶段监听 Esc 关闭（并阻止冒泡），关闭时把焦点还给原元素；点遮罩关闭。
- 返回：`null` 或面板 DOM（`role="dialog"`、`aria-modal`）。
- 调用者：`shell/Review.tsx:17`、`shell/Sync.tsx:27`、`views/AskPanels.tsx:39`。

### `ConfirmButton({ children, confirm, onConfirm, className = "btn", disabled })`
- 不能在页面上撤销的操作用的按钮：第一次点击在原位显示确认文字和"确定/取消"，第二次（确定）才执行；6 秒内不确认自动收回。旧页面用 `confirm()`，有的浏览器在这种方式打开的页面里会拦截。
- props：`children` 按钮文字；`confirm` 确认提示；`onConfirm` 确定后执行；`className`；`disabled`。
- 调用者：`shell/Review.tsx:21`、`shell/Sync.tsx:66`、`views/MediaLibrary.tsx:359`。

### `Kbd({ children })`
- 键帽样式的 `kbd`。调用者：`shell/Review.tsx:34`。
