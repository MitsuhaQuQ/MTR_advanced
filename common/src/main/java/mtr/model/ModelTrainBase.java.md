# ModelTrainBase.java

MTR 列车模型的基类：`render(…, NameColorDataBase data, ResourceLocation texture, …)` 按门开度算门的位移，按车灯选渲染层，依次画车灯、车内、细节（门上装饰）、车外、常亮灯各阶段（子类的 `render(…, RenderStage, …)`），半透明一遍画车内半透明，近处细节时再画目的地文字。

本分支的改动（0.73.5）：开头先调 `BakedTrainModels.draw`；它处理了（自带车型、门全关、在世界里、ANTE 缓冲可用）就跳过各阶段的逐个方块绘制，门上装饰和目的地文字照旧。
