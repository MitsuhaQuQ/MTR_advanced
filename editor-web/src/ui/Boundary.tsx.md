# ui/Boundary.tsx

React 错误边界：把某一层工作区里的渲染错误留在该层，页面其余部分（顶栏、草稿、应用）照常工作。被 `App.tsx` 包在当前层的工作区外。

## 导出
### `class Boundary`（组件）
- props：`children`；`resetKey` 当前层 ID，变化时清除错误重新尝试。
- state：`error`（捕获的错误或 null）、`key`（上次的 `resetKey`）。
- `getDerivedStateFromError(error)`：记下错误。
- `getDerivedStateFromProps(props, state)`：`resetKey` 变了（打开了另一层）则清错误并记新键。
- `componentDidCatch(error)`：`console.error` 输出。
- `render()`：无错误时渲染 `children`；有错误时显示"这一层出错了，草稿不受影响。"、错误信息和"重新载入页面"按钮（`location.reload()`）。
- 调用者：`App.tsx:70`。
