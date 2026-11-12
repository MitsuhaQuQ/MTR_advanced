# ConfigScreen.java（上游文件，本分支有改动）

客户端界面：MTR 客户端设置界面（对应 `Config` 各项），可选的"时间与风同步"复选框（有权限时，从仪表板打开），底部列出赞助者。打开：`common/src/main/java/mtr/screen/DashboardScreen.java:124`（带时间风同步）、`fabric/src/main/java/mtr/ModMenuConfig.java:11`（Mod Menu，无参构造）。

本分支改动：新增"显示调试信息"按钮（`Config.showDebugInfo`）；按钮位置改为紧跟最长选项文字之后而不是贴宽屏右边，并给每行加条纹与悬停高亮（0.66.4，避免把一行文字与下一行按钮看错）；时间风同步复选框单独占一行；继承 `ScreenBase`。

## 类 `ConfigScreen`（extends `ScreenBase`，implements `IGui`）

### 字段与常量
- 当前值副本：`useMTRFont`、`showAnnouncementMessages`、`useTTSAnnouncements`、`hideSpecialRailColors`、`hideTranslucentParts`、`shiftToToggleSitting`、`languageOptions`、`useDynamicFPS`。
- `hasTimeAndWindControls`（需要 `ClientData.hasPermission()`）、`useTimeAndWindSync`。
- 控件：时间风同步复选框（变化即 `sendUseTimeAndWindSyncC2S`）、各开关按钮、语言选项按钮、三个滑块（轨道纹理偏移、动态纹理分辨率、列车渲染距离百分比 `(值+1)*100/16`）、赞助按钮（打开 Patreon 网址）。
- `BUTTON_WIDTH = 60`、`buttonX`、`BUTTON_HEIGHT = TEXT_HEIGHT + TEXT_PADDING`。

### 构造函数
- `ConfigScreen()`：无时间风控件。
- `ConfigScreen(boolean useTimeAndWindSync)`：有时间风控件（仍需权限）。
- 私有完整构造：各按钮点击即调用 `Config.setXxx` 并更新文字。

### `protected void init()`
- `Config.refreshProperties()` 后读取当前值；有时间风控件时其下方整体下移一行；按钮列在 `getButtonX()`；`Keys.LIFTS_ONLY`（只含电梯的版本）时只显示 MTR 字体与赞助；设置按钮文字与滑块值。

### `private int getButtonX()`
- 按钮起始 x：最长选项文字宽度之后（包含"按潜行切换坐下"的按键名），在 `[SQUARE_SIZE, width − SQUARE_SIZE − 60]` 内。

### `void render(…)`
- 标题；偶数行条纹、悬停行高亮；各选项文字；赞助者按档位分列（金额 < 1000 显示为匿名，超出屏幕高度换列）。

### `void onClose()`
- 保存三个滑块值；`DATA_CACHE.sync()`、刷新动态纹理、`SIGNAL_BLOCKS.writeCache()`（使分辨率等变化生效）。

### `private static void setButtonText(Button, boolean state)`
- `options.mtr.on` / `options.mtr.off`。
