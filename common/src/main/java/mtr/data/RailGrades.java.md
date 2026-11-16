# RailGrades.java（0.87.0 新增）

节点坡度（设计文档 9.2）：有坡度的轨道（`Rail.hasGrades`）在节点处都取节点的坡度，高度才能平顺通过节点。节点坡度存在节点方块实体里（`BlockNode.setGrade`），没设时（默认）由相连轨道的平均坡度算出（组件 `VerticalProfile.autoNodeGrade`）。坡度的正方向是节点自己的朝向（`BlockNode.getAngle`，即刷子显示的蓝线），不随相连轨道增减而变。

服务端在节点的轨道连上、拆掉、转向之后和节点设置改了之后调 `refreshNode`（经 `RailwayData.refreshNodeGrades`，它把改过的轨道标记保存并发给玩家）；客户端刷子界面和节点悬浮文字读 `getNodeGrade`。

## 类 `RailGrades`（final，工具类）

### `public static double getNodeGrade(BlockGetter world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos)`
- 节点坡度（沿节点朝向）：设了取设的值，否则 `getAutoGrade`。

### `public static double getAutoGrade(BlockGetter world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos)`
- 每条相连轨道：平均坡度 = （另一端高度 − 本节点高度）/ 长度；离开方向与节点朝向点积 ≥ 0 为前侧；该轨道或其反向轨道是旧式（无坡度，非缆车）时节点为 0。

### `public static boolean hasOldRails(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos)`
- 节点有没有 0.87.0 以前铺的轨道（在节点处保持水平）。刷子界面纵向选项卡据此提示。

### `public static List<BlockPos> refreshNode(BlockGetter world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos)`
- 把节点坡度和竖曲线半径（`BlockNode.getVerticalRadius`）设到每条有坡度的相连轨道这一端：离开的轨道起点坡度 = 前侧 g、背侧 −g；反向轨道终点坡度取负。就地改（`setGradeStart`/`setGradeEnd`），平面形状、长度、ANTE 附加数据都不变。旧式轨道不动。
- 返回：改了的轨道的另一端节点。

### `public static void startGrades(BlockGetter world, BlockPos posStart, BlockPos posEnd, Rail rail, Rail backwardsRail)`
- 新连的轨道和反向轨道先设两端坡度 0、半径取两端节点的，随后 `refreshNode` 设上节点坡度。调用者：`ItemRailModifier.onConnect`。

## 已知限制
- 存车线已生成的路径（`PathData`）里存的是轨道副本，节点坡度改后要等路径重新生成，列车在这些轨道上的高度才跟着变；轨道本身的渲染立即变。
- 旧轨道不自动升级；要跟随坡度须拆掉重连。ANTE 旧的 `vertical_curve_radius` 不转换。
