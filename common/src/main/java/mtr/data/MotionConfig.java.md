# MotionConfig.java

运动规则配置（设计编号 R4）。列车现在只按一套规则运行：组件的规则（含速度曲线），由组件模拟器驱动它能运行的车厂的列车。旧设置（迁移到组件之前的旧规则、模拟器关闭或仅对比模式）已移除；如果仍有旧的 `mtr_motion.json`，只读取它并在日志里说明这些设置已不再生效。在模组初始化时由 `MTR.java` 调用。

## final class MotionConfig

### 常量
- `FILE_NAME = "mtr_motion.json"`：旧配置文件名。

### `private MotionConfig()`
工具类，禁止实例化。

### `public static void load(Path path)`
- 作用：若文件存在，读取 JSON；当 `useLegacyMotion == true` 或 `componentDrive != 2` 时向标准输出打印一条"旧设置已失效、列车按组件规则运行"的提示。
- 参数：`path` 配置文件路径。
- 返回：无。
- 副作用：仅 `System.out` 日志；读取失败打印 "Failed to read ... motion settings" 并吞掉异常。不改任何全局状态。
- 主要调用者：`MTR.java:591`（同一初始化段在 587–591 行）。
