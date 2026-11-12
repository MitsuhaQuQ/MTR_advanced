# SpeakerListeners.java（本分支新增）

服务端音箱（`BlockAudioSpeaker`）的听众集合：一旦玩家开始听到某个循环播放，就一直把他算作听众，直到他离开世界或音箱停止，这样走出范围再走回来时循环不会被重新开始（由客户端自己按距离调音量）。被 `common/src/main/java/mtr/block/BlockAudioSpeaker.java:94` 的方块实体持有；测试 `common/src/test/java/mtr/audio/SpeakerListenersTest.java`。

## 类 `SpeakerListeners`（final）

### 字段 `listeners`
- 当前听众的 UUID 集合。

### `Set<UUID> update(Set<UUID> present, Set<UUID> audible, boolean refresh)`
- 作用：去掉已不在世界中（不在 `present`）的听众；`audible`（在可听范围内）且在世界中的新玩家加入听众并列入通知；`refresh` 为真时所有听众都列入通知（例如设置改变需重新发包）。
- 参数：`present` 世界中的玩家；`audible` 当前在范围内的玩家。
- 返回：需要发送播放消息的玩家。

### `Set<UUID> clear()`
- 清空并返回之前的听众（用于通知他们停止）。
