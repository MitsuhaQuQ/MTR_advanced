# legacy/Slot.tsx

新页面借用旧页面元素、绘制旧代码菜单的 React 组件。旧元素在 [stage.ts](stage.ts.md) 的隐藏舞台上，`LegacySlot` 挂载时把它移进布局、卸载时放回；旧代码继续往里画并保留事件处理。`LegacyMenu` 接管 `legacy/engine.ts` 的 `menuHost`，把旧代码给出的 `[label, action]` 菜单项画成 React 菜单（支持二级菜单）。

## 导出
### `LegacySlot({ id, className, style, onResize })`（组件）
- props：`id` 旧页面元素 ID；`className` 追加到外层 `div` 的类名（外层固定有 `legacy legacy-slot`）；`style` 外层样式；`onResize` 外层尺寸变化时回调（用 `ResizeObserver`，可选）。
- 行为：`useLayoutEffect` 中取 `legacyElement(id)`，设 `hidden = false` 并 `appendChild` 进外层；清理时 `returnToStage`。
- 返回：外层 `div`。
- 调用者：`views/Inspector.tsx:23`（`inspector`）、`views/Layers.tsx:65`（`view-network`）、`views/Layers.tsx:252`（`canvas-wrap`）、`views/Navigator.tsx:24`（`tree`）。

### `LegacyMenu()`（组件）
- 无 props。状态：`menu`（`{x, y, items}`，视口像素）和 `sub`（展开的二级菜单：父项序号、位置、项）。
- 挂载时设置 `menuHost.current = { open, close }`：`open` 清掉二级菜单并显示新菜单，项为空时显示一项灰色"这里没有可用的操作"；`close` 关闭全部。卸载时置 `null`。
- 菜单打开时在窗口上监听：捕获阶段 `pointerdown`（点在菜单外则关闭）、`keydown`（Esc 关闭）、`blur`（关闭）。
- `useLayoutEffect` 把菜单挪回窗口内（距边 8px）。
- 内部 `list(items, level)`：把项画成按钮；`action` 为空则禁用；为数组则是子菜单，第 0 层鼠标移入或点击时在右侧展开 `sub`；为函数则点击后先关闭菜单再执行。
- 返回：菜单 `div` 或 `null`。
- 调用者：`App.tsx:76`（Shell 中常驻一个）。

### `run(name, ...args)`
- 按名字执行旧页面函数：`legacy.fn[name](...args)`，返回其返回值。
- 调用者：在 `src` 内未找到导入它的地方（未确定是否仍在使用）。
