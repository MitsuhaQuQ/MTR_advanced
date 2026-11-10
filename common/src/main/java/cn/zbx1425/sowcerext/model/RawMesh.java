package cn.zbx1425.sowcerext.model;

import cn.zbx1425.sowcer.batch.MaterialProp;
import cn.zbx1425.sowcer.model.Mesh;
import cn.zbx1425.sowcer.object.IndexBuf;
import cn.zbx1425.sowcer.object.VertBuf;
import cn.zbx1425.sowcer.util.OffHeapAllocator;
import cn.zbx1425.sowcer.util.DrawContext;
import cn.zbx1425.sowcer.vertex.VertAttrMapping;
import cn.zbx1425.sowcer.vertex.VertAttrSrc;
import cn.zbx1425.sowcer.vertex.VertAttrType;
import cn.zbx1425.sowcerext.model.integration.FaceList;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.math.Vector3f;
import org.lwjgl.opengl.GL11;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;

public class RawMesh {

    public final MaterialProp materialProp;
    public List<Vertex> vertices = new ArrayList<>();
    public List<Face> faces = new ArrayList<>();
    /**
     * What is left of the vertices and faces after {@link #compact()}, or null before it.
     */
    private float[] compactVertices;
    private int[] compactFaces;

    private static final int FLOATS_PER_VERTEX = 8;
    private static final int VERTICES_PER_FACE = 3;

    public RawMesh(MaterialProp materialProp) {
        this.materialProp = materialProp;
    }

    public RawMesh(DataInputStream dis) throws IOException {
        this.materialProp = new MaterialProp(dis);
        int numVertices = dis.readInt();
        this.vertices = new ArrayList<>(numVertices);
        for (int i = 0; i < numVertices; i++) this.vertices.add(new Vertex(dis));
        int numFaces = dis.readInt();
        this.faces = new ArrayList<>(numFaces);
        for (int i = 0; i < numFaces; i++) this.faces.add(new Face(dis));
    }

    public void append(RawMesh nextMesh) {
        if (nextMesh == this) throw new IllegalStateException("Mesh self-appending");
        int vertOffset = vertices.size();
        vertices.addAll(nextMesh.vertices);
        for (Face face : nextMesh.faces) {
            Face newFace = face.copy();
            for (int i = 0; i < newFace.vertices.length; ++i) {
                newFace.vertices[i] += vertOffset;
            }
            faces.add(newFace);
        }
    }

    public void appendTransformed(RawMesh nextMesh, Matrix4f mat, int color, int light) {
        if (nextMesh == this) throw new IllegalStateException("Mesh self-appending");
        int vertOffset = vertices.size();
        for (Vertex vertex : nextMesh.vertices) {
            Vertex newVertex = new Vertex(mat.transform(vertex.position), mat.transform3(vertex.normal));
            newVertex.u = vertex.u;
            newVertex.v = vertex.v;
            newVertex.color = color;
            newVertex.light = light;
            vertices.add(newVertex);
        }
        for (Face face : nextMesh.faces) {
            Face newFace = face.copy();
            for (int i = 0; i < newFace.vertices.length; ++i) {
                newFace.vertices[i] += vertOffset;
            }
            faces.add(newFace);
        }
    }

    public void clear() {
        vertices.clear();
        faces.clear();
    }

    public void validateVertIndex() {
        for (Face face : faces) {
            for (int vertIndex : face.vertices) {
                if (vertIndex < 0 || vertIndex >= vertices.size()) {
                    throw new IndexOutOfBoundsException("RawMesh contains invalid vertex index "
                            + vertIndex + " (Should be 0 to " + (vertices.size() - 1) + ")");
                }
            }
        }
    }

    public void triangulate() {
        List<Face> newFaces = new ArrayList<>();
        for (Face face : faces) {
            newFaces.addAll(Face.triangulate(face.vertices, false));
        }
        faces.clear();
        faces.addAll(newFaces);
    }

    /** Removes duplicate vertices and faces from the mesh. */
    public void distinct() {
        // if (vertices.size() > 10000 || faces.size() > 10000) return;
        // if (vertices.size() > 0) return;

        final DistinctList<Vertex> distinctVertices = new DistinctList<>(vertices.size());
        final DistinctList<Face> distinctFaces = new DistinctList<>(faces.size());

        for (Face face : faces) {
            for (int i = 0; i < face.vertices.length; ++i) {
                face.vertices[i] = distinctVertices.add(vertices.get(face.vertices[i]));
            }
            distinctFaces.add(face);
        }

        // Meshes can share these lists as well as the faces changed above, so the lists have to stay the same objects
        vertices.clear();
        vertices.addAll(distinctVertices.items);
        faces.clear();
        faces.addAll(distinctFaces.items);
    }

    /**
     * Generates normals for vertices without a normal vector. Produces duplicate vertices, and vertices that already have a
     * normal are the same object in several places, so {@link #distinct()} has to run before any vertex is changed.
     */
    public void generateNormals() {
        final List<Vertex> newVertices = new ArrayList<>(faces.size() * 3);
        for (Face face : faces) {
            if (face.vertices.length >= 3) {
                int i0 = face.vertices[0];
                int i1 = face.vertices[1];
                int i2 = face.vertices[2];
                double ax = vertices.get(i1).position.x() - vertices.get(i0).position.x();
                double ay = vertices.get(i1).position.y() - vertices.get(i0).position.y();
                double az = vertices.get(i1).position.z() - vertices.get(i0).position.z();
                double bx = vertices.get(i2).position.x() - vertices.get(i0).position.x();
                double by = vertices.get(i2).position.y() - vertices.get(i0).position.y();
                double bz = vertices.get(i2).position.z() - vertices.get(i0).position.z();
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                double t = nx * nx + ny * ny + nz * nz;
                if (t != 0.0) {
                    t = 1.0 / Math.sqrt(t);
                    float mx = (float) (nx * t);
                    float my = (float) (ny * t);
                    float mz = (float) (nz * t);
                    for (int j = 0; j < face.vertices.length; j++) {
                        Vertex vertex = vertices.get(face.vertices[j]);
                        newVertices.add(vecIsZero(vertex.normal) ? vertex.copyWithNormal(new Vector3f(mx, my, mz)) : vertex);
                        face.vertices[j] = newVertices.size() - 1;
                    }
                } else {
                    for (int j = 0; j < face.vertices.length; j++) {
                        Vertex vertex = vertices.get(face.vertices[j]);
                        newVertices.add(vecIsZero(vertex.normal) ? vertex.copyWithNormal(new Vector3f(0.0f, 1.0f, 0.0f)) : vertex);
                        face.vertices[j] = newVertices.size() - 1;
                    }
                }
            }
        }
        vertices = newVertices;
    }

    /**
     * Keeps the first of equal items in order. Models can have millions of faces, where a hash map's entries and boxed
     * indices cost more than the search itself, so this only keeps one int for each slot.
     */
    private static final class DistinctList<T> {

        private static final int HASH_MULTIPLIER = 0x9E3779B9;
        private static final int MIN_BITS = 4;

        private final List<T> items;
        private final int[] slots;
        private final int shift;

        /**
         * @param maxItems the most items this will hold, which keeps the slots at most half full
         */
        private DistinctList(int maxItems) {
            items = new ArrayList<>(maxItems);
            final int bits = Math.max(MIN_BITS, Integer.SIZE - Integer.numberOfLeadingZeros(maxItems) + 1);
            slots = new int[1 << bits];
            shift = Integer.SIZE - bits;
        }

        /**
         * @return where this item or an equal one is in the list
         */
        private int add(T item) {
            int slot = item.hashCode() * HASH_MULTIPLIER >>> shift;
            while (true) {
                // Slots hold the index plus one, so that zero is empty
                final int index = slots[slot] - 1;
                if (index < 0) {
                    items.add(item);
                    slots[slot] = items.size();
                    return items.size() - 1;
                }
                if (items.get(index).equals(item)) {
                    return index;
                }
                slot = slot + 1 & slots.length - 1;
            }
        }
    }

    public void upload(Mesh mesh, VertAttrMapping mapping) {
        _uploadAsync(mapping).accept(mesh);
    }

    public Mesh upload(VertAttrMapping mapping) {
        validateVertIndex();
        VertBuf vertBufObj = new VertBuf();
        IndexBuf indexBufObj = new IndexBuf(faces.size(), GL11.GL_UNSIGNED_INT);
        Mesh target = new Mesh(vertBufObj, indexBufObj, materialProp);
        upload(target, mapping);
        return target;
    }

    public Supplier<Mesh> uploadAsync(VertAttrMapping mapping) {
        validateVertIndex();
        Consumer<Mesh> consumer = _uploadAsync(mapping);
        return () -> {
            VertBuf vertBufObj = new VertBuf();
            IndexBuf indexBufObj = new IndexBuf(faces.size(), GL11.GL_UNSIGNED_INT);
            Mesh target = new Mesh(vertBufObj, indexBufObj, materialProp);
            consumer.accept(target);
            return target;
        };
    }

    private Consumer<Mesh> _uploadAsync(VertAttrMapping mapping) {
        distinct();

        ByteBuffer vertBuf = OffHeapAllocator.allocate(vertices.size() * mapping.strideVertex);
        for (int i = 0; i < vertices.size(); ++i) {
            if (mapping.sources.get(VertAttrType.POSITION).inVertBuf()) {
                Vector3f pos = vertices.get(i).position;
                // vertBuf.position(getVertBufPos(mapping, i, VertAttrType.POSITION));
                vertBuf.putFloat(pos.x()).putFloat(pos.y()).putFloat(pos.z());
            }
            if (mapping.sources.get(VertAttrType.COLOR).inVertBuf()) {
                // vertBuf.position(getVertBufPos(mapping, i, VertAttrType.COLOR));
                vertBuf.putInt(vertices.get(i).color);
            }
            if (mapping.sources.get(VertAttrType.UV_TEXTURE).inVertBuf()) {
                float u = vertices.get(i).u;
                float v = vertices.get(i).v;
                // vertBuf.position(getVertBufPos(mapping, i, VertAttrType.UV_TEXTURE));
                vertBuf.putFloat(u).putFloat(v);
            }
            if (mapping.sources.get(VertAttrType.UV_LIGHTMAP).inVertBuf()) {
                // vertBuf.position(getVertBufPos(mapping, i, VertAttrType.UV_LIGHTMAP));
                vertBuf.putInt(vertices.get(i).light);
            }
            if (mapping.sources.get(VertAttrType.NORMAL).inVertBuf()) {
                Vector3f mojNormal = vertices.get(i).normal.copy();
                mojNormal.normalize();
                // vertBuf.position(getVertBufPos(mapping, i, VertAttrType.NORMAL));
                vertBuf.put((byte) (mojNormal.x() * 0x7F)).put((byte) (mojNormal.y() * 0x7F)).put((byte) (mojNormal.z() * 0x7F));
            }
            for (int k = 0; k < mapping.paddingVertex; k++) vertBuf.put((byte)0);
        }

        ByteBuffer indexBuf = OffHeapAllocator.allocate(faces.size() * 3 * 4);
        for (Face face : faces) {
            for (int j = 0; j < face.vertices.length; ++j) {
                indexBuf.putInt(face.vertices[j]);
            }
        }

        boolean[] executed = new boolean[] {false};
        return mesh -> {
            if (executed[0]) return;
            executed[0] = true;
            mesh.vertBuf.upload(vertBuf, VertBuf.USAGE_STATIC_DRAW);
            OffHeapAllocator.free(vertBuf);
            mesh.indexBuf.upload(indexBuf, VertBuf.USAGE_STATIC_DRAW);
            mesh.indexBuf.setFaceCount(faces.size());
            OffHeapAllocator.free(indexBuf);
        };
    }

    private static int getVertBufPos(VertAttrMapping mapping, int vertId, VertAttrType type) {
        return mapping.strideVertex * vertId + mapping.pointers.get(type);
    }

    private static boolean vecIsZero(Vector3f vec) {
        return vec.x() == 0.0F && vec.y() == 0.0F && vec.z() == 0.0F;
    }

    public void applyMatrix(Matrix4f matrix) {
        for (Vertex vertex : vertices) {
            vertex.position = matrix.transform(vertex.position);
            vertex.normal = matrix.transform3(vertex.normal);
        }
    }

    public void applyTranslation(float x, float y, float z) {
        for (Vertex vertex : vertices) {
            vertex.position.add(x, y, z);
        }
    }

    public void applyRotation(Vector3f axis, float angle) {
        for (Vertex vertex : vertices) {
            vertex.position.rotDeg(axis, angle);
            vertex.normal.rotDeg(axis, angle);
        }
    }

    public void applyScale(float x, float y, float z) {
        float rx = (float) (1.0 / x);
        float ry = (float) (1.0 / y);
        float rz = (float) (1.0 / z);
        float rx2 = rx * rx;
        float ry2 = ry * ry;
        float rz2 = rz * rz;
        boolean reverse = x * y * z < 0.0;
        for (Vertex vertex : vertices) {
            vertex.position.mul(x, y, z);
            float nx2 = vertex.normal.x() * vertex.normal.x();
            float ny2 = vertex.normal.y() * vertex.normal.y();
            float nz2 = vertex.normal.z() * vertex.normal.z();
            float u = nx2 * rx2 + ny2 * ry2 + nz2 * rz2;
            if (u != 0.0) {
                u = (float) Math.sqrt((nx2 + ny2 + nz2) / u);
                vertex.normal.mul(rx * u, ry * u, rz * u);
            }
        }

        if (reverse) {
            for (Face face : faces) {
                face.flip();
            }
        }
    }

    public void applyMirror(boolean vx, boolean vy, boolean vz, boolean nx, boolean ny, boolean nz) {
        for (Vertex vertex : vertices) {
            vertex.position.mul(vx ? -1 : 1, vy ? -1 : 1, vz ? -1 : 1);
            vertex.normal.mul(nx ? -1 : 1, ny ? -1 : 1, nz ? -1 : 1);
        }

        int numFlips = 0;
        if (vx) numFlips++;
        if (vy) numFlips++;
        if (vz) numFlips++;

        if (numFlips % 2 != 0) {
            for (Face face : faces) {
                face.flip();
            }
        }
    }

    public void applyUVMirror(boolean u, boolean v) {
        for (Vertex vertex : vertices) {
            if (u) vertex.u = 1 - vertex.u;
            if (v) vertex.v = 1 - vertex.v;
        }
    }

    public void applyShear(Vector3f dir, Vector3f shear, float ratio) {
        for (Vertex vertex : vertices) {
            float n1 = ratio * (dir.x() * vertex.position.x() + dir.y() * vertex.position.y() + dir.z() * vertex.position.z());
            Vector3f offset1 = shear.copy();
            offset1.mul(n1);
            vertex.position.add(offset1);
            if (!vecIsZero(vertex.normal)) {
                float n2 = ratio * (shear.x() * vertex.normal.x() + shear.y() * vertex.normal.y() + shear.z() * vertex.normal.z());
                Vector3f offset2 = dir.copy();
                offset2.mul(-n2);
                vertex.normal.add(offset2);
                vertex.normal.normalize();
            }
        }
    }
    
    public void setRenderType(String type) {
        materialProp.translucent = false;
        materialProp.writeDepthBuf = true;
        materialProp.cutoutHack = false;
        materialProp.attrState = materialProp.attrState.copy();
        materialProp.attrState.lightmapUV = null;
        switch (type) {
            case "exterior":
                materialProp.shaderName = "rendertype_entity_cutout";
                break;
            case "exteriortranslucent":
                materialProp.shaderName = "rendertype_entity_translucent_cull";
                materialProp.translucent = true;
                break;
            case "interior":
                materialProp.shaderName = "rendertype_entity_cutout";
                materialProp.attrState.setLightmapUV(15 << 4 | 15 << 20);
                break;
            case "interiortranslucent":
                materialProp.shaderName = "rendertype_entity_translucent_cull";
                materialProp.translucent = true;
                materialProp.attrState.setLightmapUV(15 << 4 | 15 << 20);
                break;
            case "light":
                materialProp.shaderName = "rendertype_beacon_beam";
                materialProp.cutoutHack = true;
                break;
            case "lighttranslucent":
                materialProp.shaderName = "rendertype_beacon_beam";
                materialProp.translucent = true;
                materialProp.writeDepthBuf = false;
                break;
            default:
                throw new IllegalArgumentException("Invalid render type: " + type);
        }
    }

    /**
     * Swaps the vertices and faces for arrays once a mesh is uploaded, as an object for each vertex takes several times the
     * memory, and models can have millions of them. Only {@link #writeBlazeBuffer} still reads the mesh after that, which
     * is what shader packs need. The lists are replaced and not cleared, as other meshes can share them.
     */
    public void compact() {
        if (compactFaces != null || faces.stream().anyMatch(face -> face.vertices.length != VERTICES_PER_FACE)) {
            return;
        }

        final float[] newVertices = new float[vertices.size() * FLOATS_PER_VERTEX];
        int vertexIndex = 0;
        for (Vertex vertex : vertices) {
            newVertices[vertexIndex++] = vertex.position.x();
            newVertices[vertexIndex++] = vertex.position.y();
            newVertices[vertexIndex++] = vertex.position.z();
            newVertices[vertexIndex++] = vertex.normal.x();
            newVertices[vertexIndex++] = vertex.normal.y();
            newVertices[vertexIndex++] = vertex.normal.z();
            newVertices[vertexIndex++] = vertex.u;
            newVertices[vertexIndex++] = vertex.v;
        }

        final int[] newFaces = new int[faces.size() * VERTICES_PER_FACE];
        int faceIndex = 0;
        for (Face face : faces) {
            for (int vertex : face.vertices) {
                newFaces[faceIndex++] = vertex;
            }
        }

        compactVertices = CompactArrays.share(newVertices);
        compactFaces = CompactArrays.share(newFaces);
        vertices = new ArrayList<>();
        faces = new ArrayList<>();
    }

    boolean sharesCompactDataWith(RawMesh other) {
        return compactVertices != null && compactVertices == other.compactVertices && compactFaces == other.compactFaces;
    }

    public int getVertexCount() {
        return compactVertices == null ? vertices.size() : compactVertices.length / FLOATS_PER_VERTEX;
    }

    public double getRadiusSquared() {
        double radiusSquared = 0;
        if (compactVertices == null) {
            for (Vertex vertex : vertices) {
                final double x = vertex.position.x();
                final double y = vertex.position.y();
                final double z = vertex.position.z();
                radiusSquared = Math.max(radiusSquared, x * x + y * y + z * z);
            }
        } else {
            for (int offset = 0; offset < compactVertices.length; offset += FLOATS_PER_VERTEX) {
                final double x = compactVertices[offset];
                final double y = compactVertices[offset + 1];
                final double z = compactVertices[offset + 2];
                radiusSquared = Math.max(radiusSquared, x * x + y * y + z * z);
            }
        }
        return radiusSquared;
    }

    public int getFaceCount() {
        return compactFaces == null ? faces.size() : compactFaces.length / VERTICES_PER_FACE;
    }

    private Vertex getCompactVertex(int index) {
        final int offset = index * FLOATS_PER_VERTEX;
        final Vertex vertex = new Vertex(
                new Vector3f(compactVertices[offset], compactVertices[offset + 1], compactVertices[offset + 2]),
                new Vector3f(compactVertices[offset + 3], compactVertices[offset + 4], compactVertices[offset + 5])
        );
        vertex.u = compactVertices[offset + 6];
        vertex.v = compactVertices[offset + 7];
        return vertex;
    }

    /**
     * Gets the lists back for the few things that need them. Colour and light aren't kept, as they don't affect a mesh once uploaded.
     */
    private void expand() {
        if (compactFaces != null) {
            final List<Vertex> newVertices = new ArrayList<>(getVertexCount());
            for (int i = 0; i < getVertexCount(); i++) {
                newVertices.add(getCompactVertex(i));
            }
            final List<Face> newFaces = new ArrayList<>(getFaceCount());
            for (int i = 0; i < compactFaces.length; i += VERTICES_PER_FACE) {
                newFaces.add(new Face(new int[]{compactFaces[i], compactFaces[i + 1], compactFaces[i + 2]}));
            }
            vertices = newVertices;
            faces = newFaces;
            compactVertices = null;
            compactFaces = null;
        }
    }

    public void writeBlazeBuffer(FaceList vertexConsumer, Matrix4f matrix, int color, int light, int overlay, DrawContext drawContext) {
        drawContext.recordBlazeAction(getFaceCount());
        if (compactFaces != null) {
            for (int i = 0; i < compactFaces.length; i += VERTICES_PER_FACE) {
                final Vertex[] transformedVertices = new Vertex[VERTICES_PER_FACE];
                for (int j = 0; j < VERTICES_PER_FACE; j++) {
                    final Vertex vertex = getCompactVertex(compactFaces[i + j]);
                    transformedVertices[j] = new Vertex(matrix.transform(vertex.position), matrix.transform3(vertex.normal));
                    transformedVertices[j].u = vertex.u;
                    transformedVertices[j].v = vertex.v;
                }
                vertexConsumer.addFace(transformedVertices, color, light, overlay);
            }
            return;
        }
        for (Face face : faces) {
            assert face.vertices.length == 3;
            Vertex[] transformedVertices = new Vertex[face.vertices.length];
            for (int i = 0; i < face.vertices.length; i++) {
                Vector3f transformedPosition = matrix.transform(this.vertices.get(face.vertices[i]).position);
                Vector3f transformedNormal = matrix.transform3(this.vertices.get(face.vertices[i]).normal);
                transformedVertices[i] = new Vertex(transformedPosition, transformedNormal);
                transformedVertices[i].u = this.vertices.get(face.vertices[i]).u;
                transformedVertices[i].v = this.vertices.get(face.vertices[i]).v;
            }
            vertexConsumer.addFace(transformedVertices, color, light, overlay);
        }
    }

    /**
     * A compact mesh stays compact, as parsed models are copied from several threads and need to stay small.
     */
    public RawMesh copy() {
        RawMesh result = new RawMesh(this.materialProp.copy());
        if (compactFaces != null) {
            result.vertices = new ArrayList<>(getVertexCount());
            for (int i = 0; i < getVertexCount(); i++) {
                result.vertices.add(getCompactVertex(i));
            }
            result.faces = new ArrayList<>(getFaceCount());
            for (int i = 0; i < compactFaces.length; i += VERTICES_PER_FACE) {
                result.faces.add(new Face(new int[]{compactFaces[i], compactFaces[i + 1], compactFaces[i + 2]}));
            }
            return result;
        }
        for (Vertex vertex : this.vertices) result.vertices.add(vertex.copy());
        for (Face face : this.faces) result.faces.add(face.copy());
        return result;
    }

    public RawMesh copyForMaterialChanges() {
        RawMesh result = new RawMesh(this.materialProp.copy());
        result.vertices = vertices;
        result.faces = faces;
        result.compactVertices = compactVertices;
        result.compactFaces = compactFaces;
        return result;
    }

    public void serializeTo(DataOutputStream dos) throws IOException {
        expand();
        materialProp.serializeTo(dos);
        dos.writeInt(vertices.size());
        for (Vertex vertex : vertices) vertex.serializeTo(dos);
        dos.writeInt(faces.size());
        for (Face face : faces) face.serializeTo(dos);
    }

    public void setMatixProcess(Function<Matrix4f, Matrix4f> matrixProcess) {
        materialProp.setMatixProcess(matrixProcess);
    }
}
