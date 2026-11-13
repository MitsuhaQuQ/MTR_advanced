# OutLeads.java

按到首站晚了多少校正车厂发运用列车出库的提前量（用户 2026-11-13：出库提前量按实际跑的记下来自动校正；试跑后定为按首站晚点校正、没用就停）。纯 Java，没有宿主；两个宿主都由自己的 `BufferRecorder.outListener` 喂它：游戏 `Depot`（把它要采用的值放进 `addedBuffers`），日模拟工具 `SimulateDay`（`-Pmtr.buffersFile` 时直接改各车厂 `dispatcher.addedBuffers`）。采用的出库余量由 `DepotDispatcher.wayOutMillis` 加到出库用时上，列车当场提前发车。

## 类 `OutLeads`（final）

### 常量
- `RUNS = 3`：每条出库攒够 3 班取中间值做一次判断。

### `synchronized long add(String key, long latenessMillis, long adoptedMillis)`
- 参数：出库的键（`Buffers.key(OUT, 存车线, 首站站台)`）、这一班到首站晚了多少（早到为负）、这条出库现在采用的毫秒。
- 返回：现在要采用的毫秒，−1 为不变。
- 规则：已判为没用的不再处理。攒够 `RUNS` 班取中间值 L：
  - L < `BufferRecorder.TOLERANCE_MILLIS`（15 秒）：不变。
  - 上次提前了 R，而 L > 上次的 L − R/2（提前后晚点没少一半）：提前没用（在路上等按钟点走的东西，如折返线上的正线列车），返回 采用值 − R（退回），记为没用、记下仍晚的 L。
  - 否则提前 L（不超过 `BufferRecorder.MAX_EXCESS_MILLIS`），记下这次的 L 和提前量。

### `synchronized Map<String, Long> getIneffective()`
提前没用的出库 → 仍晚的毫秒。

### `synchronized void clear()`
全部忘掉（玩家清空实测余量时），没用的出库会再试。

## 试跑（六线图日模拟，2026-11-13）
只按用时、只往大调时，-1739… 存车线首站晚点 +66 秒降到 +4 秒；但 9045… 和 -5812… 的车在折返线上等正线车，提前发车到首站时刻不变，余量每天越加越多（80 → 932 秒）。改成本类以后的结果见 `MTR-代码说明与流程.md` 0.75.0。
