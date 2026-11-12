# TrainIdentity.java

"列车身份"（设计编号 R1，P2-C 之后）：让一列车无论到哪里都被视为同一列车的那些东西——乘客、货物，以及其它模块为它保存的数据（例如 ANTE 的 `TrainAnteState`）。当一列车作为同一车池中另一停车线（Siding）的列车继续运行时，会为那条停车线新建一个 `Train` 对象并重置运行状态，但持有同一个 `TrainIdentity`。由 `Train` 持有（`Train.java:135` 字段 `identity`）；ANTE 通过 `TrainMixin` 访问。

## final class TrainIdentity

### 字段
- `public final Set<UUID> ridingEntities`：正在乘坐这列车的实体 UUID。
- `SimpleContainer inventory`（包内可见）：货物，每节车厢一个槽；客户端为 `null`。`Train` 构造时若为 `null` 则按车厢数新建（`Train.java:194-197`）。
- `private final Map<String, Object> attachments`：其它模块按字符串键保存的附加数据。

### `public <T> T getAttachment(String key, Supplier<T> make)`
- 作用：取得某模块为这列车保存的附加对象；首次请求时用 `make` 创建并存入。
- 参数：`key` 模块自定的键；`make` 创建默认对象的工厂。
- 返回：该键对应的对象（未检查的类型转换，调用方需保证同一键类型一致）。
- 副作用：首次调用时写入 `attachments`。
- 主要调用者：`TrainMixin.java:60`；测试 `TrainIdentityTest.java:66`、`TrainIdentityTest.java:75`。
