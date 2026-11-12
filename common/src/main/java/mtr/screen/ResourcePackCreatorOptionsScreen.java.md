# ResourcePackCreatorOptionsScreen.java（上游文件，本分支仅兼容性改动）

客户端界面：资源包制作器的选项页——选择模型、属性、纹理文件，填写车辆 id、名称、颜色、贯通道与车门挡板纹理 id、乘客偏移，导出资源包。编辑的是全局的 `RenderTrains.creatorProperties`（`ResourcePackCreatorProperties`）。打开：`common/src/main/java/mtr/screen/ResourcePackCreatorScreen.java:98`；关闭后回到该界面。

本分支改动：继承 `mtr.compat.ScreenBase`，文字绘制经 `GuiCompat`；无逻辑变化。

## 类 `ResourcePackCreatorOptionsScreen`（extends `ScreenBase`，implements `IResourcePackCreatorProperties`, `ICustomResources`, `IGui`）

### 字段与常量
- `resourcePackCreatorScreen`：返回的界面。
- 按钮：`buttonChooseModelFile`、`buttonChoosePropertiesFile`、`buttonChooseTextureFile`（打开 `FileUploaderScreen` 选文件后调用 `loadModelFile/loadPropertiesFile/loadTextureFile`）、`buttonDone`、`buttonExport`（`creatorProperties.export()`）。
- 文本框：`textFieldId`（默认提示 `my_custom_train_id`）、`textFieldName`、`textFieldGangwayConnectionId`（默认 `mtr:textures/entity/sp1900`）、`textFieldTrainBarrierId`（默认 `mtr:textures/entity/r211`）；`colorSelector`。
- `sliderRiderOffset`：0…18，对应乘客偏移 `(值 − 2) / 4`（−0.5…4，单位方块）。
- 各标签文字常量。

### 构造函数 `ResourcePackCreatorOptionsScreen(ResourcePackCreatorScreen)`
- 创建上述控件与回调。

### `protected void init()`
- 垂直居中布局：三个文件按钮、id + 颜色、名称、贯通道 id、车门挡板 id、乘客偏移滑块、完成与导出。文本框变化时把格式化后的值写入 `creatorProperties`（id 用 `formatText(…, false)`，纹理 id 用 `formatText(…, true)`）。

### `void tick()` / `void render(…)` / `void onClose()`
- 文本框 tick；绘制标签；关闭时返回制作器界面。

### `private void updateControls(boolean formatTextFields)`
- 文件按钮显示已选文件名（未选为 `gui.mtr.file_upload`）；同步滑块；`formatTextFields` 时用当前属性填充文本框与颜色（颜色为 0 时以选择器颜色写回）；id、名称非空且模型、属性、纹理三个文件都已选择时才允许导出。

### `private void onUpdateColor()`
- 写入颜色。

### `private void buttonCallback(Consumer<Path> callback)`
- 打开 `FileUploaderScreen`，选了文件则以第一个路径回调（异常打印）。

### `private static String formatText(WidgetBetterTextField, String text, boolean isFileName)`
- 转小写，去掉非单词字符（文件名还保留 `:` 与 `/`），去掉开头的非字母；与原文不同则改写文本框。返回结果。
