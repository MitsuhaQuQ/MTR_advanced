# TicketMachineScreen.java（上游文件，本分支有改动）

客户端界面：售票机——显示余额与背包中绿宝石数，按钮用 2^i 个绿宝石充值。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:418`（已打开时不重复打开）。

本分支改动：继承 `mtr.compat.ScreenBase`、文字经 `GuiCompat.draw`；按钮数与充值金额改为取自 `TicketSystem`（`ADD_BALANCE_OPTIONS`、`getAddAmount`），与服务器共用同一规则。

## 类 `TicketMachineScreen`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段与常量
- `buttons`：充值按钮；`balanceText`：`gui.mtr.balance`(余额)。
- `BUTTON_COUNT = TicketSystem.ADD_BALANCE_OPTIONS`、`BUTTON_WIDTH = 80`。

### 构造函数 `TicketMachineScreen(int balance)`
- 第 i 个按钮：发送 `PacketTrainDataGuiClient.addBalanceC2S(充值金额, 2^i 绿宝石)` 并关闭界面。

### `protected void init()`
- 按钮右对齐，每行一个。

### `void tick()`
- 绿宝石不足 2^i 时禁用第 i 个按钮。

### `void render(PoseStack, int mouseX, int mouseY, float delta)`
- 绘制余额、绿宝石数和每行"用 2^i 绿宝石充值 N"。异常打印。

### `boolean isPauseScreen()`
- false。

### `private int getEmeraldCount()`
- 玩家背包中绿宝石数；无玩家为 0。

### `private static int getAddAmount(int index)`
- `TicketSystem.getAddAmount(index)`。
