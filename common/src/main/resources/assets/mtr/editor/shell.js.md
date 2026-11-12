# shell.js

旧页面（`index.html`）高级编辑器的外壳：一个页面上两个工作区——时刻表编辑器（`editor.js`）和媒体库（`media.js`），在顶栏切换而不重新加载页面，运行图的缩放、滚动和选择因此保留。游戏以 `#key=…` 打开页面；这里读一次密钥供两者使用，之后地址栏表示工作区：`#/timetable` 或 `#/media/<种类>`（如 `#/media/audio`）。媒体库脚本在第一次打开时才加载。`body` 的 `data-ws` 表示显示哪个工作区；标了 `data-ws` 的元素只在对应工作区显示（`editor.css`）。新页面不加载本文件，而由 `editor-web/src/api/client.ts` 以相同的 `sessionStorage` 键读取密钥，并在 `legacy/engine.ts` 的 `env.MTREditor` 中提供替身。

## 全局
### `MTREditor`（IIFE 返回的对象）
- `key`：编辑器密钥：地址里的 `key=` 存入 `sessionStorage` 的 `mtr-editor-key` 并使用；没有时读 `sessionStorage`；存储不可用时只用地址里的；都没有为空串。使用者：`editor.js:44`。
- `ws`（getter）：当前工作区。使用者：`media.js:162`。
- `part`（getter）：工作区内的部分（如 `audio`）。使用者：`media.js:502`。
- `open(ws, part)`：打开工作区，路由变化时 `history.pushState` 新历史项（后退可回来），然后 `show()`。使用者：`media.js:400`。
- `inTimetable()`：当前是否在时刻表工作区。使用者：`editor.js:45`。

## 内部
- `WORKSPACES = ["timetable", "media"]`。
- `parse(hash)`：从地址解析 `{ ws, part }`，不认识的为 `timetable`。
- `hashOf(route)`：`#/<ws>[/<part>]`。
- `route`：当前路由；`mediaLoading`：媒体库脚本是否已开始加载。
- `show()`：设置 `body.dataset.ws`；更新所有 `[data-ws-tab]` 标签的 `aria-selected` 和 `tabIndex`；首次进入媒体工作区时动态插入 `<script src="media.js">`；派发 `mtr:workspace` 事件（`detail: { ws, part }`）。
- 加载时：`history.replaceState` 把密钥从地址去掉并写成规范的工作区地址；监听 `hashchange` 和 `popstate`（`follow`：路由真有变化才 `show()`）；工作区标签点击时 `open`（切到当前工作区时保留其部分，否则用标签的 `data-ws-part`）；`.ws-tabs` 上左右方向键在标签间移动并聚焦；最后 `show()`。
