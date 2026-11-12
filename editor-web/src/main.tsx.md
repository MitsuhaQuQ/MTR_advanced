# main.tsx

新页面 `editor-web`（React + Vite）的入口脚本：引入样式 `styles.css` 和剩余旧元素的主题 `legacy-theme.css`，在 `index.html` 的 `#root` 上用 `createRoot` 渲染 [`App`](App.tsx.md)。没有导出，也没有函数。

## 顶层语句
- `createRoot(document.getElementById("root")!).render(<App />)`：挂载整个页面。副作用：建立 React 根；`App` 引入 `legacy/engine.ts`，从而在加载时创建旧代码引擎。
- 调用者：由 `editor-web/index.html` 以模块脚本加载（Vite 入口）。
