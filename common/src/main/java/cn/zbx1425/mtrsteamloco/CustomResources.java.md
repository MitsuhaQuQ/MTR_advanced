# CustomResources.java（ANTE）

ANTE 的资源重载：`reset` 重新加载着色器、清空模型管理器和其他缓存，读入资源包里的列车、轨道和装饰；`resetComponents` 给现有列车换上新的渲染器和声音。

本分支的改动（0.73.5）：`reset` 同时 `mtr.model.BakedTrainModels.clear()`，自带车型的烘焙缓冲在下次画时按新贴图重建。
