# SoundManagerMixin.java（fabric）

客户端 mixin，注入原版 `SoundManager`。一是实现 `mtr.client.SoundManagerAccess`，把原版私有的声音事件注册表 `registry` 暴露给 `UploadedSounds`，让服务器下发的广播音频能作为声音事件加入游戏；二是在每次游戏重新加载声音（资源重载）结束后调用 `UploadedSounds.refresh()` 把这些声音重新加回去（重载会清空注册表）。登记于 `fabric/src/main/resources/mtr.mixins.json:17`；Forge 侧有逐字相同的 `forge/src/main/java/mtr/mixin/SoundManagerMixin.java`。

## 类 `SoundManagerMixin`（abstract，implements `SoundManagerAccess`）

### 影子字段 `registry`（`Map<ResourceLocation, WeighedSoundEvents>`）
- 原版声音事件 id → 可选声音集合。

### `Map<ResourceLocation, WeighedSoundEvents> mtr$getSoundRegistry()`
- 返回 `registry` 本身（可修改的原 Map）。调用者：`UploadedSounds`（通过 `SoundManagerAccess` 强转）。

### `private void afterApply(CallbackInfo ci)`
- 注入点：`SoundManager.apply(Preparations, ResourceManager, ProfilerFiller)` 的 `TAIL`（资源重载应用声音之后）。
- 作用：`UploadedSounds.refresh()`。
