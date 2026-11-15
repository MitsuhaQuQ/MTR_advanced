# SimLog.java（0.83.4 新增）

组件运行中要告诉宿主日志的话（运用互换、运用没跑、没有备车接替等）统一从这里打。

- `say(String)`：本线程没在安静运行时 `System.out.println`。调用者：`TrainService`、`DepotDispatcher`。
- `isQuiet()`：本线程是否在安静运行。
- `quietly(Supplier<T>)`：本线程安静地跑完再恢复原状（可嵌套），返回结果。调用者：`Rehearsal.run`（预演的列车不是游戏的，它的话不进游戏日志）。
- 别的线程不受影响（`ThreadLocal`）。测试：`SimLogTest`。
