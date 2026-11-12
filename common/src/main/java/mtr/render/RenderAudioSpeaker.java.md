# RenderAudioSpeaker.java（本分支新增）

客户端：音箱方块实体渲染器。音箱方块本身不可见，这里按其设置的"外观"方块（默认唱片机）画一个普通方块，使音箱可以伪装成任意模型方块。在 `common/src/main/java/mtr/MTRClient.java:102` 注册给 `AUDIO_SPEAKER_TILE_ENTITY`。

## 类 `RenderAudioSpeaker`（final，extends `BlockEntityRendererMapper<BlockAudioSpeaker.TileEntityAudioSpeaker>`）

### 构造函数 `RenderAudioSpeaker(BlockEntityRenderDispatcher dispatcher)`

### `void render(TileEntityAudioSpeaker speaker, float delta, PoseStack matrices, MultiBufferSource buffers, int light, int overlay)`
- 作用：`Minecraft.getBlockRenderer().renderSingleBlock(speaker.appearance(), …)`，用方块所在处光照绘制外观方块。
