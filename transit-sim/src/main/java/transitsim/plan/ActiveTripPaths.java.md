# ActiveTripPaths.java

重新生成运用（duties）后，新生成的路径表里可能已经没有某些列车正在使用的路径 key。本文件提供一个静态工具：把"仍有列车在用、且仍然有效"的旧 `TripPath` 补回新的路径表，避免正在跑的列车丢失路径。属于 `transitsim.plan`，只依赖 `TripPath`/`PlanTrack`；由游戏侧 `mtr.data.Siding` 在替换路径表时调用。

## 类 `ActiveTripPaths`（final，工具类）

### `private ActiveTripPaths()`
私有构造，禁止实例化。

### `public static <T extends PlanTrack> void retain(Map<String, TripPath<T>> replacement, Collection<TripPath<T>> active, Predicate<TripPath<T>> valid)`
- 作用：遍历 `active`，对每条非空路径，若 `replacement` 中没有它的 `key` 且 `valid.test(path)` 为真，就 `replacement.put(path.key, path)`。
- 参数：
  - `replacement`：新生成的路径表，key 为 `TripPath.key`；会被就地修改。
  - `active`：当前列车正在使用的路径集合，元素可为 null（跳过）。
  - `valid`：判断旧路径是否仍可用的谓词（例如轨道是否仍存在），由调用方给出。
- 返回值：无。
- 副作用：向 `replacement` 加入条目；已存在的 key 不覆盖。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1234`、`Siding.java:1300`、`Siding.java:1896`。
