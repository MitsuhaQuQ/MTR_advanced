# scripts/gen-legacy.mjs

构建脚本（Node）：从旧编辑器（`common/src/main/resources/assets/mtr/editor/` 下的 `editor.js`、`editor.css`、`index.html`）生成新页面使用的三个文件，使新页面在迁移期间运行旧页面自己的代码、两页行为始终一致：
- `src/legacy/engine.generated.js`：导出 `createLegacy(env)`，内含 `editor.js` 的主体；
- `src/legacy/markup.generated.ts`：导出 `LEGACY_MARKUP`，旧 `index.html` 的 `<body>` 内容（去掉 `<script>`），由 `legacy/stage.ts` 放到隐藏舞台；
- `public/legacy.css`：`editor.css` 包在 `.legacy { … }` 里（CSS 嵌套），只作用于新页面的 `.legacy` 容器。

`editor.js` 是一个 `(() => { … })()` 函数。脚本取其主体（到 `// ---------- Start ----------` 启动代码之前）放进工厂函数 `createLegacy(env)`，区别是：菜单由 React 画；端点和保存草稿由新页面接管；新页面自己画的面板的 `render*` 改为通知 React；启动代码（读草稿、轮询、选今天）省略，由 store 完成。事件处理注册在新页面赋予旧 ID 的元素上（`stage.ts`），新页面没有的旧按钮落在占位元素上、永远不会触发。由 `build.sh` 和 `npm run gen`（`package.json`）运行；`npm run build` 也会先运行它。输出已提交，所以 `npm run dev` 不需要先运行。注释说第 7 步旧页面移除时这些文件会变成源码并拆成模块（目前状态未确定）。

## 常量
- `here`、`EDITOR`（旧编辑器目录）、`OUT`、`CSS_OUT`：路径。
- `FROM_ENV`：不再取自 `editor.js`、改由 `env` 提供的名字。按退役步骤分组：菜单与请求（`openMenu` … `setTreeOpen`）；配线图对话框；第 3 步车次与行（`core/services.ts`）；第 4 步时刻表规则；第 5 步运用规则；第 6 步全网设置；第 7 步调度与模拟（均经 `legacy/retired.ts`）；第 8 步小对话框（`legacy/dialogs.ts`）；新页面有自己面板的对话框（`stopsDialog`、`newLineDialog`、…、`releasePlanDialog`）；折返站台（`turnOf`、`toggleTurnPlatform`）。
- `WRAPPED`：由 `env` 提供、但旧函数保留为 `old<Name>` 供新页面回退的名字。当前为空数组。
- `oldName(name)`：`foo` → `oldFoo`。
- `NOTIFY = ["draw"]`：照旧运行，然后调用 `env.render(name, args)`：旧运行图的 `draw()` 被很多旧函数单独调用，新页面的运行图（`views/RunningDiagram.tsx`）借此重画。
- `RENDERS`：新页面自己画的面板：`renderAll`、`renderTree`、`renderInspector`、`renderViews`、`renderBanner`、`renderStatus`、`renderSyncButton`、`renderDraftState`，旧函数整体换成 `return env.render(name, args)`。

## 主流程（顶层语句）
1. 读 `editor.js`（统一换行为 `\n`），定位主体起止；格式不符时抛错提示更新脚本。
2. `declarations(src)`（`scripts/extract.mjs`）取顶层声明：主体内全部声明，加上启动代码里非函数、且不含 `pickToday` 的声明（如 `nowDay` 所需常量）。
3. 检查上面各列表中的名字都存在，否则抛错。
4. 生成替换：`FROM_ENV` 的声明换成注释 `/* name: from env */`；`WRAPPED` 改名为 `old<Name>`；`NOTIFY` 改名为 `<name>Before` 并追加包装函数；`RENDERS` 换成调用 `env.render`。按位置从后往前替换。
5. 收集函数名（`fn`）和变量名（`v`）：`let` 变量生成 getter/setter，`const` 只有 getter。
6. 写出 `engine.generated.js`：从 `env` 解构 `MTREditor` 和 `FROM_ENV`/`WRAPPED` 中的名字，放入主体，返回 `{ fn: {…}, v: {…} }`。
7. 写出 `markup.generated.ts`、`public/legacy.css`，打印函数和变量数。
- 末尾注释：媒体库是新页面自己的（`views/MediaLibrary.tsx`），旧页面仍自己运行 `media.js`。

## 调用者
- `package.json` 的 `gen`、`build` 脚本；注释中提到的 `build.sh`。
