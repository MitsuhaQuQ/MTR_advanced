# RailGeometryCache.java

每条轨道一份、只缓存一种宽度的轨道几何点缓存（渲染用）。缓存键是几何参数数组和倾角（roll）映射；不保留光照、相机或世界引用。被 ANTE 的 `RailMixin` 用于轨道渲染时复用顶点数据。与组件无关。

## final class RailGeometryCache

### 字段
- `key`：上次构建所用几何参数的拷贝。
- `rolls`：上次构建所用倾角映射（位置 → 角度）的拷贝。
- `points`：缓存的浮点点数组。

### `public float[] get(double[] geometry, Map<Double, Float> currentRolls, Supplier<float[]> build)`
- 作用：若缓存为空或 `geometry`/`currentRolls` 与缓存键不同（按内容比较），调用 `build` 重新生成；否则返回缓存。
- 参数：`geometry` 描述轨道形状的参数；`currentRolls` 当前倾角映射；`build` 生成点数组的函数。
- 返回：点数组（新建或缓存）。
- 副作用：结果长度 ≤ 81920 个 float 时写入缓存；超过则清空缓存（避免极长轨道长期占内存，注释称普通轨道每段 10 个 float）。
- 主要调用者：`RailMixin.java:462`；测试 `RailGeometryCacheTest.java:16`。
