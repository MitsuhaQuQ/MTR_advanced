# NodeAngleScreen.java（本分支新增）

客户端界面：用刷子点轨道节点后打开的节点设置——节点角度（可在右侧俯视图 `NodeAnglePreview` 上拖动设置）、过渡（目前固定 0，不可编辑）、关键节点、折返停留时间（节点有折返轨时）、寻路边界（整个节点或其某条轨道）。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:200`。

## 类 `NodeAngleScreen`（extends `ScreenBase`，implements `IGui`）

### 字段与常量
- `pos`：节点；`angle`：初始角度（优先取节点上轨道的朝向 `Rail.getNodeFacing`，否则方块角度 `BlockNode.getAngle`）。
- `textFieldAngle`（有符号小数）、`textFieldTransition`（只读 0）、`checkboxKeyNode`、`textFieldTurnBack`（正整数）、`turnBackRail`（与该节点相连、任一方向为 `RailType.TURN_BACK` 的轨道）。
- `checkboxBoundary`（节点为寻路边界）、`connectedNodes` 与 `checkboxSegments`（每条相连轨道单独作为边界，按距离排序）。
- `preview`、`draggingPreview`、`buttonSet`、`buttonCancel`。
- `MAX_VALUE_LENGTH = 6`、`LABEL_WIDTH = 150`、`ROW_HEIGHT`、行号常量（角度 0、方向说明 1、规则说明 2、过渡 4、关键节点 5、折返 6、边界 7、轨道边界从 8 起）、预览尺寸 64…240、标签文字。

### 构造函数 `NodeAngleScreen(BlockPos pos)`
- 计算初始角度与折返轨，列出相连节点并建复选框（`gui.mtr.node_boundary_rail`(x, y, z)）。

### `protected void init()`
- 布局各行；关键节点按 `ClientData.KEY_NODES`；折返时间显示 `turnBackDwell / 2`（`turnBackDwell` 以半秒为单位，界面显示秒；0 显示为空）；边界按 `ClientData.PATH_REGIONS.boundaries`；预览放在右侧，尽量大（不足 64 时不显示）；角度文本框填 `BlockNode.getAngleValue(angle)`。只有存在折返轨时才加入折返文本框。

### `void tick()`
- 文本框 tick。

### `void render(…)`
- 角度标签、方向说明（`getAngleDirectionText`）、角度规则说明（最多两行）、预览（返回的错误或提示文字显示在图下方，错误为红色）、过渡与折返标签、边界状态（`getBoundaryStatus`）。

### `boolean mouseClicked(…)` / `boolean mouseDragged(…)` / `boolean mouseReleased(…)`
- 在预览内按下左键开始拖动并设置角度，拖动中持续设置，松开结束。

### `private void setAngleFromPreview(double mouseX, double mouseY)`
- 节点指向鼠标；按住 Shift 以 22.5° 为步长。

### `boolean isPauseScreen()`
- false。

### `private void onSet()`
- 发 `sendNodeAngleC2S(pos, 角度, 关键节点, 折返停留)`（无折返轨为 −1，空为 0，否则秒 × 2）；节点边界有变化时发 `sendPathBoundaryC2S(pos, null, 选中)`；每条轨道边界有变化时发 `sendPathBoundaryC2S(pos, 另一端, 选中)`；关闭。数字格式错误时不做任何事。

### `private Component getAngleDirectionText()`
- `gui.mtr.node_angle_direction`(输入角度对应的方向 `BlockNode.getAngleDirection`, 玩家当前朝向 `BlockNode.getAngleText(getPlayerAngle)`)，输入无效时方向为 "-"。

### `private static Rail getTurnBackRail(BlockPos pos)`
- 返回第一条正向或反向为折返轨的相连轨道（正向的那条），没有为 null。

### `private int getButtonsRow()`
- 按钮行号 = 8 + 轨道复选框数。

### `private String getBoundaryStatus()`
- 提示：道岔节点（相连 > 2）勾选节点边界时警告会分隔每个分支（`gui.mtr.node_turnout_boundary`）；节点边界或某条轨道边界没有切开任何东西（两侧仍是同一区域，`uncutNodes`/`uncutSegments`）时警告；否则有边界时显示区域数（`gui.mtr.node_regions`），无边界为 null。

### `private static int getRowY(int row)`
- `SQUARE_SIZE + ROW_HEIGHT * row`。
