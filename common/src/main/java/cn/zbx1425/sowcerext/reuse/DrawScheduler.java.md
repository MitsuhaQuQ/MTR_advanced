# DrawScheduler.java（ANTE sowcer）

模型绘制的排队和提交：`enqueue` 收集本帧的模型和姿态，`commit` 时不透明部分进 `BatchManager`（GPU 缓冲）或写进原版缓冲（`drawWithBlaze`），半透明部分按设置排序后写缓冲或直接用 GPU 缓冲，最后 `commitRaw` 画出。

本分支的改动（0.73.4，存车线帧率）：开着半透明排序时，只有离相机 32 格内（`TRANSLUCENT_SORT_DISTANCE_SQ`，按姿态矩阵的平移算，`isNear`）的模型走排序写缓冲；更远的直接用上传好的缓冲画，和关掉排序一样。以前 20 列资源包列车每帧把全部车窗排序写缓冲，占一帧的 60%。
