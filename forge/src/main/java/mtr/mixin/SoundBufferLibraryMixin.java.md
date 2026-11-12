# SoundBufferLibraryMixin.java（forge）

客户端 mixin，注入原版 `SoundBufferLibrary.getCompleteBuffer`（按资源位置加载整段音频缓冲）。服务器上传给客户端的广播音频（报站声音）不在资源包里，而是客户端本地保存的文件，由 `mtr.client.UploadedSounds` 管理；本 mixin 让这些路径从 `UploadedSounds` 取音频。登记于 `forge/src/main/resources/mtr.mixins.json:16`；Fabric 侧有逐字相同的 `fabric/src/main/java/mtr/mixin/SoundBufferLibraryMixin.java`。

## 类 `SoundBufferLibraryMixin`（abstract）

### 影子字段 `cache`（`Map<ResourceLocation, CompletableFuture<SoundBuffer>>`）
- 原版的音频缓冲缓存，传给 `UploadedSounds.getBuffer` 以便同样缓存上传音频。

### `private void getCompleteBuffer(ResourceLocation location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir)`
- 注入点：`getCompleteBuffer` 的 `HEAD`，可取消。
- 作用：`UploadedSounds.isUploadedPath(location)` 为真时，返回 `UploadedSounds.getBuffer(cache, location)`，跳过原版从资源包读取。
- 参数：`location` 声音文件的资源位置。
- 副作用：`getBuffer` 可能往 `cache` 里放入条目（见 `UploadedSounds`）。
