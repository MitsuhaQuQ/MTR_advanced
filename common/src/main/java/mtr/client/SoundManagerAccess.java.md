# SoundManagerAccess.java（本分支新增）

客户端接口：各加载器的 `SoundManagerMixin`（`fabric/src/main/java/mtr/mixin/SoundManagerMixin.java:22`、Forge 同名文件）让原版 `SoundManager` 实现它，从而 `UploadedSounds` 可以把服务器的广播音频加进游戏已知的声音里（`common/src/main/java/mtr/client/UploadedSounds.java:45`、`:48`）。

## 接口 `SoundManagerAccess`

### `Map<ResourceLocation, WeighedSoundEvents> mtr$getSoundRegistry()`
- 返回原版声音事件注册表本身（可修改）。
