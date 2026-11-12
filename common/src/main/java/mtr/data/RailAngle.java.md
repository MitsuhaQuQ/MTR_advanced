# RailAngle.java

轨道朝向角度。本分支把原 MTR 的 16 个固定方向扩展为 0.1° 精度：全部 3600 个实例在类加载时预先创建并缓存，所以角度可以直接用 `==` 比较。保留 16 个罗盘方向常量与 `fromAngle(float)` 以兼容基于原版构建的附加模组。被 `Rail`（轨道几何）、`BlockNode`（节点方块角度）、`PathFinder`、`CompoundCreator` 等广泛使用。

## final class RailAngle

### 字段（每个实例）
- `angleDegrees`（float）：角度，规范化到 `[-180, 180)`。
- `angleRadians`、`sin`、`cos`、`tan`、`halfTan`（`tan(θ/2)`）：预计算的三角值。
- `units`：以 0.1° 为单位的整数角（0…3599）。

### 常量
- `DEGREES_IN_CIRCLE = 360`、`UNITS_PER_DEGREE = 10`、`UNITS_IN_CIRCLE = 3600`。
- `QUADRANT_UNITS = 225`（22.5°）、`QUADRANTS = 16`（public）、`ANGLE_INCREMENT = 22.5`。
- `ANGLES[3600]`：缓存的全部实例。
- 罗盘常量：`E`(0)、`SEE`、`SE`、`SSE`、`S`(90°)、`SSW`、`SW`、`SWW`、`W`、`NWW`、`NW`、`NNW`、`N`、`NNE`、`NE`、`NEE`，依次相隔 22.5°（Minecraft 中 +z 为南，所以 90° 为 S）。

### `private RailAngle(int units)`
- 作用：由 0.1° 单位创建并预计算三角值。只在静态块调用。

### `public RailAngle getOpposite()`
- 返回：相反方向（+180°）。
- 主要调用者：`Rail.java:182`、`DisplacementTool.java:88`。

### `public RailAngle add(RailAngle railAngle)` / `public RailAngle sub(RailAngle railAngle)`
- 返回：角度相加/相减（模 360°）。
- 主要调用者：`Rail.java:185`、`PathFinder.java:233`（sub）。

### `public RailAngle getRotatedFacing(RailAngle referenceFacing, RailAngle railFacing)`
- 作用：节点转到 `this` 角度后，一条轨道的新朝向。轨道总是朝向其另一端，不能换边，所以保持在新角度的近侧——即使新角度输入的是反方向。比较的是同一节点上的参考轨道 `referenceFacing`，而非新角度本身，因为在 90° 转动时两侧与新角度一样近。
- 逻辑：参考轨道的新朝向 = 与参考朝向点积 ≥ 0 的那个（`this` 或其反向）；`railFacing` 与参考同侧则取之，否则取反。
- 主要调用者：`Rail.java:492`；测试 `RailAngleTest.java:15`。

### `public boolean isParallel(RailAngle railAngle)`
- 返回：同向或反向。
- 主要调用者：`Rail.java:137`、`RailwayData.java:932`、`CompoundCreator.java:159`。

### `public boolean similarFacing(float newAngleDegrees)` / `public static boolean similarFacing(float a1, float a2)`
- 返回：两角之差规范化后绝对值 < 90°。
- 主要调用者：`ItemNodeModifierBase.java:81`、`MinecraftClientUtil.java:116`。

### `public String toString()`
- 返回：`angleDegrees` 的字符串。

### `public static int getQuadrant(float angleDegrees, boolean include225)`
- 返回：最近的 22.5° 档位（0–15）；`include225 == false` 时按 45° 分档（0–7）。
- 主要调用者：`BlockNode.java:72`、`BlockNode.java:300`。

### `public static RailAngle fromAngle(double angleDegrees)` / `fromAngle(float angleDegrees)`
- 返回：四舍五入到 0.1° 的缓存实例。float 版只为兼容附加模组保留。
- 主要调用者：`BlockNode.java:267`、`RailAngleExtra.java:8`。

### `public static RailAngle closestQuadrant(float angleDegrees)`
- 返回：最近的 22.5° 档位实例。
- 主要调用者：`PathFinder.java:164`。

### 私有辅助
- `fromQuadrant(int)`：档位 → 实例。
- `fromUnits(long)`：0.1° 单位（取模）→ 实例。
- `normalizeAngle(float/double)`：规范化到 `[-180, 180)`。
