# RenderCompare.java

测试模组（MTM_TestKit，只在测试客户端里）：把同一画面在某个渲染设置开、关时各截一张，逐像素比较，检查更快的画法画得一样（2026-11-13）。由 `ClientDriver` 的 `/compare` 路由调用；`C:/gtmp/fast/fps-test.sh` 的比较步骤用它，结果写进 `compare.csv`。

## 类 `RenderCompare`（final，私有构造）

### `public static JsonObject compare(Minecraft minecraft, JsonObject body) throws Exception`
- 参数：`setting`（`bake` = ANTE `ClientConfig.bakeBuiltInTrains`，0.73.5 自带车型烘焙；`optimization` = `ClientConfig.enableOptimization`，ANTE 缓冲整体开关，同时切换平面轨道的区块缓冲和 MTR 逐面画法，0.73.4），`settleMs`（切换后等多久再截，默认 1500），`tolerance`（每个通道差多少以内算相同，默认 8），`name`（保存的图片名）。
- 流程：在渲染线程读当前值、截第一张；切换设置，等 `settleMs`，截第二张；`finally` 里恢复原值。两张存到 `<客户端目录>/testkit/compare/<name>-on.png` / `-off.png`。
- 返回：`setting`、`differing`（不同的像素数）、`share`（占比）、`maxDelta`（最大通道差）、`box`（不同像素的外接矩形 `x0 y0 x1 y1`，没有时为空）。

### `private static boolean get(String setting)` / `private static void set(String setting, boolean value)`
直接读写 `ClientConfig` 上面两个字段（下一帧起生效），未知设置抛 `IllegalArgumentException`。

## 2026-11-13 的结果
20 列 sp1900 开关烘焙差 31 像素，bbmodel、OBJ 0，混合 10 列 2；开关优化 sp1900 910、bbmodel 175、混合 320（远处车窗排序等）；轨道开关优化约 2.3%，只在轨道上，是枕木条纹相位不同（MTR 按 (x+z)%4 偏移）。OBJ 20 列关掉优化时客户端内存不够（以前就有），比较脚本对 `obj*` 跳过优化比较。
