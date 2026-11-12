# PaintingImageMixin.java（forge）

服务端与客户端共用的 mixin，注入原版 `Painting` 实体，让每幅原版画多带一个"导入图片设置"字符串（JSON），并通过实体同步数据（SynchedEntityData）发给客户端、写入实体存档。实现 `mtr.image.PaintingImage` 接口，供 `ImportedPaintingRenderer`、`ImportedPaintingRendererMixin`、服务端设置画的包处理等使用。登记于 `forge/src/main/resources/mtr.mixins.json:7`；Fabric 侧有逐字相同的 `fabric/src/main/java/mtr/mixin/PaintingImageMixin.java`。

## 类 `PaintingImageMixin`（abstract，implements `PaintingImage`）

### 常量 `MTR_IMAGE`（`@Unique static EntityDataAccessor<String>`）
- 用 `SynchedEntityData.defineId(Painting.class, STRING)` 注册的同步字段 id，值为图片设置 JSON，空串表示没有导入图片。
- 注意：在 `Painting` 上新增同步字段会改变该实体的同步字段序号，客户端与服务端必须都装本模组。

### 存档键
- `"mtr_image"`（实体 NBT 字符串）：图片设置 JSON；为空时不写。

### `private SynchedEntityData mtr$data()`
- 返回本画实体的 `getEntityData()`。

### `private void mtr$define(CallbackInfo ci)`
- 注入点：`Painting.defineSynchedData` 的 `TAIL`。
- 作用：定义 `MTR_IMAGE`，默认值 `""`。

### `String mtr$getImageSettings()`（接口实现）
- 返回当前同步字段的设置字符串（可能为空串）。

### `void mtr$setImageSettings(String settings)`（接口实现）
- 作用：先 `PaintingSettings.parse(settings)` 规范化，再写回其 `json()`。
- 副作用：修改同步数据，服务端会自动同步到客户端。
- 异常：`parse` 失败时抛出的 `RuntimeException` 原样上抛（调用方处理）。

### `private void mtr$save(CompoundTag tag, CallbackInfo ci)`
- 注入点：`addAdditionalSaveData` 的 `TAIL`。作用：设置非空时写 `"mtr_image"`。

### `private void mtr$load(CompoundTag tag, CallbackInfo ci)`
- 注入点：`readAdditionalSaveData` 的 `TAIL`。
- 作用：有 `"mtr_image"` 就经 `mtr$setImageSettings` 读入；解析出错则把字段置为空串（丢弃损坏设置，不抛异常）。
