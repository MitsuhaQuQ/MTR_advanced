package cn.zbx1425.mtrsteamloco.render.integration;

import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.sowcerext.model.ModelCluster;
import cn.zbx1425.sowcerext.model.RawModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.zbx1425.sowcer.math.Matrix4f;
import mtr.mappings.ModelDataWrapper;
import mtr.mappings.ModelMapper;

import java.util.function.Supplier;

public class SowcerModelAgent extends ModelMapper {

    public ModelCluster uploadedModel;

    private RawModel pendingModel;

    private final boolean bbCoords;

    public SowcerModelAgent(RawModel rawModel, boolean bbCoords) {
        super(new ModelDataWrapper(null, 0, 0));
        this.bbCoords = bbCoords;
        if (DeferredModelUpload.isActive() && rawModel.sourceLocation == null) {
            this.pendingModel = rawModel;
            DeferredModelUpload.add(this, rawModel);
        } else {
            long startNanos = System.nanoTime();
            this.uploadedModel = MainClient.modelManager.uploadVertArrays(rawModel);
            LoadTimings.add("uploadDirect", startNanos);
        }
    }

    public Supplier<ModelCluster> prepareUpload() {
        return MainClient.modelManager.prepareVertArrays(pendingModel);
    }

    public void completeUpload(Supplier<ModelCluster> prepared) {
        // Without prepared data, for example after a timeout, upload the normal way
        this.uploadedModel = prepared != null ? prepared.get() : MainClient.modelManager.uploadVertArrays(pendingModel);
        this.pendingModel = null;
    }

    public void compactModel() {
        if (uploadedModel != null) {
            uploadedModel.compact();
        }
    }

    @Override
    public void render(PoseStack matrices, VertexConsumer vertices, float x, float y, float z, float rotateY, int light, int overlay) {
        if (uploadedModel == null) return;
        Matrix4f partPose = new Matrix4f(matrices.last().pose()).copy();
        if (!bbCoords) partPose.rotateX((float) Math.PI); // Undo MTR's blockbench compatibility rotation
        Matrix4f localPose = Matrix4f.translation(x / 16f, y / 16f, z / 16f);
        localPose.rotateY(rotateY);
        partPose.multiply(localPose);
        MainClient.drawScheduler.enqueue(uploadedModel, partPose, light);
    }


    @Override
    public void render(PoseStack matrices, VertexConsumer vertices, float x, float z, float rotateY, int light, int overlay) {
        this.render(matrices, vertices, x, 0.0F, z, rotateY, light, overlay);
    }
}
