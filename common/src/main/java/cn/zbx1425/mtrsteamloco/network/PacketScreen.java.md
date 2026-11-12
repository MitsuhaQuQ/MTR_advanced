# PacketScreen.java（ANTE）

服务端通知客户端打开某个 ANTE 界面的 S2C 包，通道 `mtrsteamloco:show_screen`。服务端在玩家右键装饰物方块（`cn/zbx1425/mtrsteamloco/block/BlockEyeCandy.java:109`）或使用复合创建器（`cn/zbx1425/mtrsteamloco/item/CompoundCreator.java:59`、`:71`）时发送；客户端接收器在 `cn/zbx1425/mtrsteamloco/MainClient.java:42` 注册。

## 包格式（字段顺序）
1. `int type`：0 = 只有界面名；1 = 带方块坐标。
2. `String screenName`：界面名。
3. `BlockPos pos`：仅 `type > 0` 时。
4. 仅当 `screenName == "rail_path_editor"`：`BlockPos pos1` 和一个 `Rail`（`new Rail(packet)` 读取）。目前没有发送方会发这个名字，客户端读出后也不使用（switch 里没有对应分支）。

## 类 `PacketScreen`

### 静态字段 `PACKET_SHOW_SCREEN`
- `ResourceLocation("mtrsteamloco", "show_screen")`。

### `static void sendScreenS2C(ServerPlayer player, String screenName)`
- 作用：写 `type = 0` 和界面名，发给该玩家。
- 调用者：`cn/zbx1425/mtrsteamloco/item/CompoundCreator.java:59`、`:71`。

### `static void sendScreenBlockS2C(ServerPlayer player, String screenName, BlockPos pos)`
- 作用：写 `type = 1`、界面名、方块坐标，发给该玩家。
- 调用者：`cn/zbx1425/mtrsteamloco/block/BlockEyeCandy.java:109`。

### `static void receiveScreenS2C(FriendlyByteBuf packet)`
- 作用：转给内部类处理（把客户端类引用隔在内部类里，避免专用服务器加载本类时连带加载客户端类）。

## 私有内部类 `MakeClassLoaderHappy`

### `static void receiveScreenS2C(FriendlyByteBuf packet)`
- 作用：在网络线程按上面格式读包，再在客户端主线程（`minecraft.execute`）打开界面：
  - `eye_candy` → `EyeCandyScreen.createScreen(pos, null)`；
  - `brush_edit_rail` → `BrushEditRailScreen.createScreen(null)`；
  - `compound_creator` → `CompoundCreatorScreen.createScreen(null)`；
  - `route_path_creator` → `RoutePathCreatorScreen.createScreen(null)`；
  - 其他名字不做任何事。
