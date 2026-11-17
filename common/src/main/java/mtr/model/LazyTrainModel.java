package mtr.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.client.DoorAnimationType;
import mtr.client.TrainModelsOnDemand;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A resource pack train's (or bogie's) model that is built only when the train is first to be drawn (0.100.0, user
 * 2026-11-17: 额外模型资源包按需载入，主要是列车). It stands in the train's properties from the reload on; the
 * renderers ask {@link #resolve} for the model to draw, which starts building it in the background the first time and
 * gives nothing until it is ready ({@link TrainModelsOnDemand}), so the car is not drawn for that while.
 */
public class LazyTrainModel extends ModelTrainBase {

	public static final int NEW = 0, LOADING = 1, READY = 2, FAILED = 3;

	public final String name;
	private final Supplier<ModelTrainBase> builder;
	private final AtomicInteger state = new AtomicInteger(NEW);
	private volatile ModelTrainBase model;
	/** What was uploaded for it (ANTE's model agents), to give back when it is let go */
	private java.util.List<?> uploads = java.util.Collections.emptyList();
	private volatile long lastUsedNanos = System.nanoTime();

	public LazyTrainModel(String name, DoorAnimationType doorAnimationType, Supplier<ModelTrainBase> builder) {
		super(doorAnimationType, false);
		this.name = name;
		this.builder = builder;
	}

	/**
	 * @return the model to draw: the model itself, or for a lazy one its built model, asking for it to be built if it
	 * isn't yet (null until it is)
	 */
	public static ModelTrainBase resolve(ModelTrainBase model) {
		if (model instanceof LazyTrainModel) {
			final LazyTrainModel lazy = (LazyTrainModel) model;
			lazy.lastUsedNanos = System.nanoTime();
			final ModelTrainBase built = lazy.model;
			if (built == null) {
				lazy.request();
			}
			return built;
		}
		return model;
	}

	/**
	 * @return the model as built so far, without asking for it (the model itself if not lazy)
	 */
	public static ModelTrainBase peek(ModelTrainBase model) {
		return model instanceof LazyTrainModel ? ((LazyTrainModel) model).model : model;
	}

	/** Starts building it in the background if it isn't built or being built */
	public void request() {
		if (state.compareAndSet(NEW, LOADING)) {
			TrainModelsOnDemand.build(this);
		}
	}

	public int getState() {
		return state.get();
	}

	/** On the loader's thread: the model built from the pack */
	public ModelTrainBase build() {
		return builder.get();
	}

	/** On the render thread, once its buffers are up */
	public void setBuilt(ModelTrainBase built, java.util.List<?> uploads) {
		model = built;
		this.uploads = uploads;
		lastUsedNanos = System.nanoTime();
		state.set(READY);
	}

	public long getLastUsedNanos() {
		return lastUsedNanos;
	}

	/** Lets the built model go, to be built again when needed: what was uploaded for it, for the caller to give back */
	public java.util.List<?> release() {
		final java.util.List<?> released = uploads;
		uploads = java.util.Collections.emptyList();
		model = null;
		state.set(NEW);
		return released;
	}

	public void setFailed() {
		state.set(FAILED);
	}

	/** Back to not built (a build dropped by a reload) */
	public void reset() {
		model = null;
		state.set(NEW);
	}

	@Override
	protected void render(PoseStack matrices, VertexConsumer vertices, RenderStage renderStage, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails) {
		// Drawn through resolve(); a caller drawing this stand-in itself gets the built model's stages
		final ModelTrainBase built = resolve(this);
		if (built != null) {
			built.render(matrices, vertices, renderStage, light, doorLeftX, doorRightX, doorLeftZ, doorRightZ, currentCar, trainCars, head1IsFront, renderDetails);
		}
	}

	@Override
	protected int getDoorMax() {
		final ModelTrainBase built = model;
		return built == null ? 1 : built.getDoorMax();
	}
}
