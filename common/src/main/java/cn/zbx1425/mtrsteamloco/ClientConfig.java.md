# ClientConfig.java（ANTE）

ANTE 的客户端设置，读写 `config/mtrsteamloco.json`：光影兼容（`enableOptimization`）、bbmodel 预加载、半透明排序、轨道渲染（3D、变形、开关）、列车渲染和声音、烟、侧倾，以及几个界面的滑条范围和脚本自定义项。

本分支的改动：
- `enableBbModelPreload` 默认开（0.73.4，存车线帧率：不开时资源包 bbmodel 列车每帧逐个方块画，20 列 3 FPS，开了约 70）。旧配置文件没有 `bbModelPreloadDefaultOn` 标记时，`load` 打开一次并在读完后 `save` 写回标记；之后按文件里的值。
- `bakeBuiltInTrains`（0.73.5，默认开，JSON 键同名，无界面）：MTR 自带车型走 `mtr.model.BakedTrainModels` 的烘焙缓冲；某个车型画得不对时可关掉。
- `useFlatRailChunks()`：渲染轨道、关了 3D 轨道且优化可用（没有光影）时为真，此时 MTR 的平面轨道也用分块缓冲画（见 `RailRenderDispatcher`、`RailModelRegistry.FLAT_RAIL_KEY`）。
- `getRailRenderLevel()`：0 不画轨道，1 MTR 的画法，2 ANTE 的 3D 轨道（网格），3 实例化。
