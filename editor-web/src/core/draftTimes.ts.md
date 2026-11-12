# core/draftTimes.ts

草稿时刻表的各站时刻由游戏计算（退役旧代码第 4 步：取代旧的 `timesSource` 与 `sendTimes`）。页面自己不算时刻：某路线的时刻表在草稿里时，最后一次改动后稍等片刻，把所有这样的路线一起发给游戏（`api/times`，发送函数由调用方提供），回答到来之前显示游戏原有的时刻；问过但没回答的草稿在下一次改动时再问。由 `legacy/retired.ts:31` 创建，供旧代码的 `timesSource` 使用。

## 类型
- `Answer`（内部）：`{ routes: [{ id, tables: [{ times: number[][], exceptions: [{ service, times }] }] }] }`。

## 导出
### `class DraftTimes`
- 字段：
  - `answered: Map<routeId, { key, tables: TimesSource }>`：已得到回答的时刻，`key` 为请求时的草稿指纹；
  - `requested: Map<routeId, key>`：已请求（或待发）的指纹；
  - `timer`：防抖计时器。
- `constructor(send, draftTimetable, routeById, answeredNow, delay = 150)`：
  - `send(routes)`：向游戏请求这些路线草稿时刻表的时刻，返回 `Answer`；
  - `draftTimetable(r)`：该路线草稿中的时刻表（没有则假值）；
  - `routeById(id)`：当前的路线对象；
  - `answeredNow()`：时刻到来后调用，用于重建车次并重绘；
  - `delay`：防抖毫秒数。
- `source(r)`：路线时刻的来源。无草稿时刻表：删除请求记录，返回 `gameSource(r)`（`core/services.ts`）。有草稿：指纹 `key = JSON.stringify([timetable, r.hasTimes ? r.fastest : null])`；缓存缺失或指纹不同且尚未以此指纹请求时，记下并安排 `flush`。返回缓存的回答，没有则 `gameSource(r)`。
- `flush()`（异步）：挑出请求了但未按该指纹回答的路线，深拷贝草稿时刻表一起 `send`；对每条回答，仅当当前草稿指纹仍等于请求时的指纹才存入 `answered`（例外转为 `Map<service, times>`）；失败则删除对应请求记录以便下次再问，并直接返回；成功后调用 `answeredNow()`。
- 调用者：`legacy/retired.ts:31`。
