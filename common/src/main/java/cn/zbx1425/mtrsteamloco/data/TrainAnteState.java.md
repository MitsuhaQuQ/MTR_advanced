# TrainAnteState.java（ANTE）

ANTE 为每列列车保存的状态，挂在 `mtr.data.TrainIdentity` 的附件（attachment）上，而不是挂在某个停车轨的 `Train` 对象上。这样列车作为同一车池中另一停车轨的列车继续运行时（设计文档中的 R1），这些状态跟着列车走。由 `TrainMixin` 通过 `identity.getAttachment(KEY, TrainAnteState::new)` 取得（`common/src/main/java/cn/zbx1425/mtrsteamloco/mixin/TrainMixin.java:59`–`:60`）。

## 类 `TrainAnteState`（final）

### 常量 `KEY = "mtrsteamloco"`
- 在 `TrainIdentity` 附件表中的键。

### 字段
- `customConfigs`（`Map<String, String>`）：列车自定义配置（键值字符串，ANTE 脚本/界面使用），存档时由 `TrainMixin` 以 `custom_configs` 键序列化。
- `configResponders`（`Map<String, ConfigResponder>`）：配置项的响应器（脚本定义的配置界面条目），只在运行时存在。
- `configsChanged`：配置是否已修改、待同步。
