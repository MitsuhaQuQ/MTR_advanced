package cn.zbx1425.sowcer.model;

import cn.zbx1425.sowcer.batch.MaterialProp;
import cn.zbx1425.sowcer.object.InstanceBuf;
import cn.zbx1425.sowcer.object.VertArray;
import cn.zbx1425.sowcer.vertex.VertAttrMapping;
import cn.zbx1425.sowcerext.model.RawMesh;
import net.minecraft.resources.ResourceLocation;
import cn.zbx1425.sowcer.math.Matrix4f;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class VertArrays implements Closeable {

    public final ArrayList<VertArray> meshList = new ArrayList<>();

    public static VertArrays createAll(Model model, VertAttrMapping mapping, InstanceBuf instanceBuf) {
        VertArrays result = new VertArrays();
        for (Mesh mesh : model.meshList) {
            VertArray meshVertArray = new VertArray();
            meshVertArray.create(mesh, mapping, instanceBuf);
            result.meshList.add(meshVertArray);
        }
        return result;
    }

    public void replaceTexture(String oldTexture, ResourceLocation newTexture) {
        for (VertArray vertArray : meshList) {
            if (vertArray.materialProp.texture == null) continue;
            String oldPath = vertArray.materialProp.texture.getPath();
            if (oldPath.substring(oldPath.lastIndexOf("/") + 1).equals(oldTexture)) {
                vertArray.materialProp.texture = newTexture;
            }
        }
    }

    public void replaceAllTexture(ResourceLocation newTexture) {
        for (VertArray vertArray : meshList) {
            vertArray.materialProp.texture = newTexture;
        }
    }

    public VertArrays copyForMaterialChanges() {
        VertArrays result = new VertArrays();
        for (VertArray vertArray : meshList) {
            VertArray newVertArray = vertArray.copyForMaterialChanges();
            result.meshList.add(newVertArray);
        }
        return result;
    }

    public void setMatixProcess(Function<Matrix4f, Matrix4f> matrixProcess) {
        for (VertArray vertArray : meshList) {
            vertArray.materialProp.setMatixProcess(matrixProcess);
        }
    }

    @Override
    public void close() {
        for (VertArray mesh : meshList) {
            mesh.close();
        }
    }
}
