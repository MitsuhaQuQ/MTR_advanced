# DataJson.java

调试专用测试模组 MTM_TestKit（`testkit/`，仅 Fabric）的工具类：把 MTR 数据对象按其自身的存档格式（msgpack）转成 JSON，使服务端和客户端两份数据用同一种方式写出、可以逐字段比较。服务端驱动 `ServerDriver`（`testkit/src/main/java/mtm/testkit/serverdriver/ServerDriver.java:292`）和客户端驱动 `ClientDriver`（`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:107`）的数据快照接口都用它。

## 类 `DataJson`（final，私有构造，只有静态方法）

### `static JsonElement of(SerializedDataBase data)`
- 作用：先写 map 头（`data.messagePackLength()` 个键），调 `data.toMessagePack(packer)`，再把得到的字节用 msgpack 解回 `Value` 并 `toJson()`，最后用 Gson 解析成 `JsonElement`。
- 参数：任一 MTR 可存档对象（车站、站台、车厂等）。
- 返回：该对象的 JSON；出错时返回 `{"error": 异常文字}`，不抛异常。

### `static JsonArray of(Collection<? extends NameColorDataBase> dataSet)`
- 作用：按 `id` 升序排序后逐个转 JSON，让两次快照的顺序一致。
- 返回：JSON 数组。

### `static JsonObject snapshot(stations, platforms, sidings, routes, depots)`
- 作用：组成 `{"stations":[…],"platforms":[…],"sidings":[…],"routes":[…],"depots":[…]}`。
- 调用者：`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:107`（`/client-data`）、`testkit/src/main/java/mtm/testkit/serverdriver/ServerDriver.java:292`。
