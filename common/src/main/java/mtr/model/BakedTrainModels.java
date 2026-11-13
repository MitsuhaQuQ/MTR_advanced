package mtr.model;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.sowcer.batch.MaterialProp;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcer.util.AttrUtil;
import cn.zbx1425.sowcerext.model.Face;
import cn.zbx1425.sowcerext.model.ModelCluster;
import cn.zbx1425.sowcerext.model.RawMesh;
import cn.zbx1425.sowcerext.model.RawModel;
import cn.zbx1425.sowcerext.model.Vertex;
import cn.zbx1425.sowcerext.reuse.ModelManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.data.IGui;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MTR's own train models (ModelSimpleTrainBase) drawn from GPU buffers built once (siding FPS, 2026-11-13): drawn the
 * usual way, every cube of every car is transformed and written again each frame, 69% of a frame with 20 parked trains
 * in view. A car with its doors shut looks the same every frame for the same car kind (head, middle, tail or single),
 * facing, detail level, lights and texture, so each stage of it is captured once into one model and the car is then one
 * draw call. The stages get the materials MTR's render layers give them: with the lights on, interiors fully lit and the
 * lights glowing; with them off (parked trains), everything takes the world's light.
 * <p>
 * Only in the world (not in screens), with ANTE's buffers usable (no shaders) and {@code bakeBuiltInTrains} on; cars with
 * doors moving or open, the destination texts and the door overlays are drawn the usual way. The buffers are dropped when
 * resources reload.
 */
public final class BakedTrainModels {

	/** Set by JonModelTrainRenderer around drawing a car in the world */
	private static boolean inWorld;
	/** While a model is captured, ModelMapper writes its cubes without going through ModelPart.render (Sodium replaces it) */
	public static boolean capturing;

	/** A ModelCluster, or NOTHING (a variant with nothing to draw) or FAILED (capturing it went wrong: drawn the usual way) */
	private static final Map<ModelTrainBase, Map<Key, Object>> CACHE = new IdentityHashMap<>();
	private static final Object NOTHING = new Object();
	private static final Object FAILED = new Object();

	private BakedTrainModels() {
	}

	public static void setInWorld(boolean value) {
		inWorld = value;
	}

	public static void clear() {
		CACHE.values().forEach(clusters -> clusters.values().forEach(cluster -> {
			if (cluster instanceof ModelCluster) {
				((ModelCluster) cluster).close();
			}
		}));
		CACHE.clear();
	}

	/**
	 * Draws the car from its buffer if it can be.
	 *
	 * @return whether the car's stages are taken care of (drawn now, or, for the translucent pass, drawn with the rest)
	 */
	static boolean draw(ModelTrainBase model, PoseStack matrices, ResourceLocation texture, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int car, int cars, boolean head1IsFront, boolean lightsOn, boolean isTranslucent, boolean renderDetails) {
		if (!inWorld || !(model instanceof ModelSimpleTrainBase) || doorLeftX != 0 || doorRightX != 0 || doorLeftZ != 0 || doorRightZ != 0) {
			return false;
		}
		if (!ClientConfig.bakeBuiltInTrains || !ClientConfig.useRenderOptimization() || !MainClient.drawScheduler.shaderManager.isReady()) {
			return false;
		}
		if (isTranslucent) {
			// The translucent stage is in the same buffer, drawn in the opaque pass
			return true;
		}
		final Key key = new Key(texture, car == 0, car == cars - 1, head1IsFront, renderDetails, lightsOn);
		final Map<Key, Object> clusters = CACHE.computeIfAbsent(model, ignored -> new HashMap<>());
		Object cluster = clusters.get(key);
		if (cluster == null) {
			try {
				cluster = capture(model, texture, car, cars, head1IsFront, renderDetails, lightsOn);
			} catch (Exception e) {
				cn.zbx1425.mtrsteamloco.Main.LOGGER.error("[MTR] Could not build the buffer of train model {} ({}); it is drawn the usual way", model.getClass().getSimpleName(), texture, e);
				cluster = FAILED;
			}
			clusters.put(key, cluster);
		}
		if (cluster == FAILED) {
			return false;
		}
		if (cluster instanceof ModelCluster) {
			MainClient.drawScheduler.enqueue((ModelCluster) cluster, new Matrix4f(matrices.last().pose()), light);
		}
		return true;
	}

	private static Object capture(ModelTrainBase model, ResourceLocation texture, int car, int cars, boolean head1IsFront, boolean renderDetails, boolean lightsOn) {
		final Capture vertices = new Capture();
		final PoseStack matrices = new PoseStack();
		model.baseTransform(matrices);
		capturing = true;
		try {
			for (final ModelTrainBase.RenderStage stage : ModelTrainBase.RenderStage.values()) {
				if (stage == ModelTrainBase.RenderStage.INTERIOR_TRANSLUCENT && !renderDetails) {
					continue;
				}
				vertices.begin(material(texture, stage, lightsOn));
				model.render(matrices, vertices, stage, 0, 0, 0, 0, 0, car, cars, head1IsFront, renderDetails);
			}
		} finally {
			capturing = false;
		}
		vertices.model.meshList.values().removeIf(mesh -> mesh.vertices.isEmpty());
		if (vertices.model.meshList.isEmpty()) {
			return NOTHING;
		}
		// The game's GL state is kept while the buffers are made, as ANTE does wherever it uploads mid-frame
		cn.zbx1425.sowcer.util.GlStateTracker.capture();
		try {
			return new ModelCluster(vertices.model, ModelManager.DEFAULT_MAPPING);
		} finally {
			cn.zbx1425.sowcer.util.GlStateTracker.restore();
		}
	}

	/**
	 * The material of a stage as ModelTrainBase.render picks its render layer and light: the lights glow and the interior
	 * is lit only with the lights on; the always-on lights always glow.
	 */
	private static MaterialProp material(ResourceLocation texture, ModelTrainBase.RenderStage stage, boolean lightsOn) {
		final MaterialProp material = new MaterialProp();
		material.texture = texture;
		switch (stage) {
			case LIGHTS:
				if (lightsOn) {
					material.shaderName = "rendertype_beacon_beam";
					material.cutoutHack = true;
				} else {
					material.shaderName = "rendertype_entity_cutout";
				}
				break;
			case INTERIOR:
				material.shaderName = "rendertype_entity_cutout";
				if (lightsOn) {
					material.attrState.setLightmapUV(AttrUtil.exchangeLightmapUVBits(IGui.MAX_LIGHT_INTERIOR));
				}
				break;
			case ALWAYS_ON_LIGHTS:
				material.shaderName = "rendertype_beacon_beam";
				material.translucent = true;
				material.writeDepthBuf = false;
				break;
			case INTERIOR_TRANSLUCENT:
				material.shaderName = "rendertype_entity_translucent_cull";
				material.translucent = true;
				if (lightsOn) {
					// The same both ways round, as the sorted path takes it unexchanged
					material.attrState.setLightmapUV(IGui.MAX_LIGHT_GLOWING);
				}
				break;
			default:
				material.shaderName = "rendertype_entity_cutout";
				break;
		}
		return material;
	}

	private static final class Key {

		private final ResourceLocation texture;
		private final boolean isEnd1Head, isEnd2Head, head1IsFront, renderDetails, lightsOn;

		private Key(ResourceLocation texture, boolean isEnd1Head, boolean isEnd2Head, boolean head1IsFront, boolean renderDetails, boolean lightsOn) {
			this.texture = texture;
			this.isEnd1Head = isEnd1Head;
			this.isEnd2Head = isEnd2Head;
			this.head1IsFront = head1IsFront;
			this.renderDetails = renderDetails;
			this.lightsOn = lightsOn;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof Key)) {
				return false;
			}
			final Key other = (Key) object;
			return Objects.equals(texture, other.texture) && isEnd1Head == other.isEnd1Head && isEnd2Head == other.isEnd2Head && head1IsFront == other.head1IsFront && renderDetails == other.renderDetails && lightsOn == other.lightsOn;
		}

		@Override
		public int hashCode() {
			return Objects.hash(texture, isEnd1Head, isEnd2Head, head1IsFront, renderDetails, lightsOn);
		}
	}

	/**
	 * Takes the vertices a model writes, four to a face, into the mesh of the material set last.
	 */
	private static final class Capture implements VertexConsumer {

		private final RawModel model = new RawModel();
		private RawMesh mesh;
		private Vertex vertex = new Vertex();

		private void begin(MaterialProp material) {
			mesh = model.meshList.computeIfAbsent(material, RawMesh::new);
		}

		@Override
		public VertexConsumer vertex(double x, double y, double z) {
			vertex.position = new Vector3f((float) x, (float) y, (float) z);
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			return this;
		}

		@Override
		public VertexConsumer uv(float u, float v) {
			vertex.u = u;
			vertex.v = v;
			return this;
		}

		@Override
		public VertexConsumer overlayCoords(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer uv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			vertex.normal = new Vector3f(x, y, z);
			return this;
		}

		@Override
		public void endVertex() {
			if (vertex.normal == null) {
				vertex.normal = new Vector3f(0, 1, 0);
			}
			mesh.vertices.add(vertex);
			if (mesh.vertices.size() % 4 == 0) {
				// The game writes quads; the buffers take triangles
				final int last = mesh.vertices.size();
				mesh.faces.addAll(Face.triangulate(new int[]{last - 4, last - 3, last - 2, last - 1}, false));
			}
			vertex = new Vertex();
		}

		@Override
		public void defaultColor(int red, int green, int blue, int alpha) {
		}

		@Override
		public void unsetDefaultColor() {
		}
	}
}
