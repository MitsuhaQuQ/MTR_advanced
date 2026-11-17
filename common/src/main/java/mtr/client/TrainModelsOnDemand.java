package mtr.client;

import cn.zbx1425.mtrsteamloco.render.integration.SowcerModelAgent;
import cn.zbx1425.mtrsteamloco.render.integration.UploadCapture;
import cn.zbx1425.sowcer.util.GlStateTracker;
import cn.zbx1425.sowcerext.model.ModelCluster;
import mtr.data.TrainClient;
import mtr.model.LazyTrainModel;
import mtr.model.ModelTrainBase;
import mtr.render.JonModelTrainRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Builds resource pack train models when they are first needed (0.100.0, user 2026-11-17: 额外模型资源包按需载入，主要是
 * 列车), instead of all of them in the resource reload. One background thread builds a model as the reload would have
 * (reading the pack, parsing, merging), with its GPU uploads captured ({@link UploadCapture}) and their vertex data
 * prepared; the render thread then makes the GL calls a few milliseconds a frame ({@link #onFrame}) and the model is
 * drawn from then on. The trains the client knows of ask for their models a little ahead of being drawn.
 * <p>
 * A reload drops what is being built (its models belong to the registrations it replaces).
 */
public final class TrainModelsOnDemand {

	/** Time a frame may spend making GL calls for models built in the background */
	private static final long UPLOAD_BUDGET_NANOS = 4_000_000;
	/** Frames between asking for the models of the trains the client knows of */
	private static final int PREFETCH_FRAMES = 20;
	/** How often models are looked at to be let go */
	private static final long RELEASE_CHECK_NANOS = 5_000_000_000L;
	/** A model not drawn (nor asked for ahead) for this long is let go */
	private static final long IDLE_NANOS = 180_000_000_000L;
	/** ... or for this long while the heap is more than {@link #HEAP_PRESSURE} full */
	private static final long IDLE_UNDER_PRESSURE_NANOS = 15_000_000_000L;
	private static final double HEAP_PRESSURE = 0.75;

	private static final Logger LOGGER = LogManager.getLogger("MTR");
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR train models");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private static final Queue<Built> BUILT = new ConcurrentLinkedQueue<>();
	/** Every on-demand model of the current reload's trains, to let the unused ones go */
	private static final List<LazyTrainModel> MODELS = new ArrayList<>();
	private static long lastReleaseCheck = System.nanoTime();
	private static int releasedCount;
	private static volatile int generation;
	private static int frames;
	private static int builtCount;
	private static long builtMillis;

	private TrainModelsOnDemand() {
	}

	/** Whether pack train models wait to be needed (ANTE's client config, on by default) */
	public static boolean isEnabled() {
		return cn.zbx1425.mtrsteamloco.ClientConfig.loadTrainModelsOnDemand;
	}

	/** A reload: models being built belong to the registrations it replaces */
	public static void onReload() {
		generation++;
		// The reload gives back every model buffer itself (ANTE's model manager is cleared)
		MODELS.clear();
		Built built;
		while ((built = BUILT.poll()) != null) {
			built.model.reset();
		}
	}

	/** A model of the current reload's trains (made in the reload, on the render thread) */
	public static void register(LazyTrainModel model) {
		MODELS.add(model);
	}

	/** Builds a model in the background (called once per model, by {@link LazyTrainModel#request}) */
	public static void build(LazyTrainModel model) {
		final int buildGeneration = generation;
		WORKER.submit(() -> {
			if (buildGeneration != generation) {
				model.reset();
				return;
			}
			final long startMillis = System.currentTimeMillis();
			final List<SowcerModelAgent> agents = UploadCapture.begin();
			try {
				final ModelTrainBase built = model.build();
				final List<Supplier<ModelCluster>> prepared = new ArrayList<>(agents.size());
				for (final SowcerModelAgent agent : agents) {
					prepared.add(agent.prepareUpload());
				}
				BUILT.add(new Built(model, built, agents, prepared, buildGeneration, System.currentTimeMillis() - startMillis));
			} catch (Throwable e) {
				LOGGER.error("On-demand: building train model {} failed", model.name, e);
				model.setFailed();
			} finally {
				UploadCapture.end();
			}
		});
	}

	/** Each frame on the render thread, before trains are drawn: uploads within the budget, and asks ahead */
	public static void onFrame() {
		if (!BUILT.isEmpty()) {
			final long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
			GlStateTracker.capture();
			try {
				Built built;
				while (System.nanoTime() < deadline && (built = BUILT.poll()) != null) {
					if (built.generation != generation) {
						built.model.reset();
						continue;
					}
					try {
						for (int i = 0; i < built.agents.size(); i++) {
							built.agents.get(i).completeUpload(built.prepared.get(i));
							built.agents.get(i).compactModel();
						}
						built.model.setBuilt(built.built, built.agents);
						builtCount++;
						builtMillis += built.millis;
						LOGGER.info("On-demand: train model {} built in {} ms ({} uploads; {} built so far in {} ms)", built.model.name, built.millis, built.agents.size(), builtCount, builtMillis);
					} catch (Exception e) {
						LOGGER.error("On-demand: uploading train model {} failed", built.model.name, e);
						built.model.setFailed();
					}
				}
			} finally {
				GlStateTracker.restore();
			}
			// ANTE's pool of shared vertex arrays only empties at the end of a reload: emptied here too, or it would keep
			// the arrays of models let go (the models in use keep their own)
			cn.zbx1425.sowcerext.model.CompactArrays.clear();
		}
		final long now = System.nanoTime();
		if (now - lastReleaseCheck > RELEASE_CHECK_NANOS) {
			lastReleaseCheck = now;
			releaseUnused(now);
		}
		if (++frames >= PREFETCH_FRAMES) {
			frames = 0;
			for (final TrainClient train : ClientData.TRAINS) {
				request(TrainClientRegistry.getTrainProperties(train.trainId));
			}
		}
	}

	/**
	 * Lets the models not drawn for a while go (user 2026-11-17: 车包多时内存能到 16-20 GB): their GPU buffers given back
	 * and their objects left to the garbage collector, to be built again if a train of that kind comes back. Sooner when
	 * the heap is filling up.
	 */
	private static void releaseUnused(long now) {
		final Runtime runtime = Runtime.getRuntime();
		final boolean pressure = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory() > HEAP_PRESSURE;
		final long idle = pressure ? IDLE_UNDER_PRESSURE_NANOS : IDLE_NANOS;
		int released = 0;
		for (final LazyTrainModel model : MODELS) {
			if (model.getState() == LazyTrainModel.READY && now - model.getLastUsedNanos() > idle) {
				for (final Object upload : model.release()) {
					if (upload instanceof SowcerModelAgent) {
						final SowcerModelAgent agent = (SowcerModelAgent) upload;
						cn.zbx1425.mtrsteamloco.MainClient.modelManager.release(agent.uploadedModel);
						agent.uploadedModel = null;
					}
				}
				released++;
			}
		}
		if (released > 0) {
			cn.zbx1425.sowcerext.model.CompactArrays.clear();
			releasedCount += released;
			builtCount -= released;
			LOGGER.info("On-demand: let {} unused train model(s) go{} ({} let go so far)", released, pressure ? " (heap filling up)" : "", releasedCount);
		}
	}

	/** Asks for the models of a train type: its cars' and its bogies' */
	public static void request(TrainProperties properties) {
		if (properties == null) {
			return;
		}
		if (properties.renderer instanceof JonModelTrainRenderer) {
			LazyTrainModel.resolve(((JonModelTrainRenderer) properties.renderer).model);
		}
		if (properties.bogie1 != null) {
			LazyTrainModel.resolve(properties.bogie1.model);
		}
		if (properties.bogie2 != null) {
			LazyTrainModel.resolve(properties.bogie2.model);
		}
	}

	private static final class Built {

		private final LazyTrainModel model;
		private final ModelTrainBase built;
		private final List<SowcerModelAgent> agents;
		private final List<Supplier<ModelCluster>> prepared;
		private final int generation;
		private final long millis;

		private Built(LazyTrainModel model, ModelTrainBase built, List<SowcerModelAgent> agents, List<Supplier<ModelCluster>> prepared, int generation, long millis) {
			this.model = model;
			this.built = built;
			this.agents = agents;
			this.prepared = prepared;
			this.generation = generation;
			this.millis = millis;
		}
	}
}
