# MTMLogicForge.java

Forge 端的空 mod 入口类，注解 `@Mod("mtm_logic")`。作用只是让本分支在 Forge 的模组列表中多显示一个独立条目 `MTM_Logic`（版本号为 `gradle.properties` 里的 `mtm_version`），与附属模组依赖的 MTR 版本号（3.2.2）分开。Forge 要求 `mods.toml` 中的每个 mod 都有一个 `@Mod` 类，所以有此类；它不含任何逻辑，代码里也没有其他地方引用它（由 Forge 按注解加载）。

## 类 `MTMLogicForge`
- 无字段、无方法；默认构造函数由 Forge 在加载 mod 时调用。
