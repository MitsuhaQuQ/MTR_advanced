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
 * <p>
 * The server sends its train types by use ({@link mtr.data.TrainUsage}, 0.101.0, user 2026-11-18: 正在活跃的车型的载入和
 * 维持优先度提高，避免被频繁卸载；根据剩余内存决策预先构建多少). The most used are built ahead, one after another, while the
 * heap is less than {@link #PRELOAD_HEAP} full, so how many depends on the memory left; those are kept until the heap is
 * nearly full ({@link #KEEP_FAVOURED_UNTIL_HEAP}), the least used of them going first, while the others go after a few
 * minutes unused.
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
	/** Models of the most used types are built ahead while the heap is less full than this */
	private static final double PRELOAD_HEAP = 0.5;
	/** Models built ahead for being much used are only let go once the heap is this full, the least used first */
	private static final double KEEP_FAVOURED_UNTIL_HEAP = 0.9;
	/** ... and not drawn for this long */
	private static final long FAVOURED_IDLE_NANOS = 60_000_000_000L;
	/** At most this many of them let go at a time */
	private static final int FAVOURED_RELEASED_AT_ONCE = 2;
	private static final long PRELOAD_CHECK_NANOS = 1_000_000_000L;

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
	/** The server's train types by use, most used first, and their scores */
	private static List<String> usageOrder = new ArrayList<>();
	private static final java.util.Map<String, Double> USAGE_SCORES = new java.util.HashMap<>();
	/** Models built ahead for their type being much used, with the score of the type */
	private static final java.util.Map<LazyTrainModel, Double> FAVOURED = new java.util.HashMap<>();
	private static final java.util.concurrent.atomic.AtomicInteger BUILDING = new java.util.concurrent.atomic.AtomicInteger();
	private static long lastPreloadCheck = System.nanoTime();
	private static int preloadedCount;
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
		FAVOURED.clear();
		preloadedCount = 0;
		Built built;
		while ((built = BUILT.poll()) != null) {
			built.model.reset();
			BUILDING.decrementAndGet();
		}
	}

	/**
	 * The server's train types by use, most used first, with the trains of each in the world now (on the render thread).
	 * A type's place in this order is what keeps its models: the least used go first.
	 */
	public static void setUsage(List<String> trainIds, List<Integer> trainsNow) {
		usageOrder = new ArrayList<>(trainIds);
		USAGE_SCORES.clear();
		for (int i = 0; i < trainIds.size(); i++) {
			USAGE_SCORES.put(trainIds.get(i), (double) (trainIds.size() - i));
		}
		// The models kept for being much used take their type's new place
		FAVOURED.replaceAll((model, score) -> 0D);
		for (final String trainId : trainIds) {
			for (final LazyTrainModel model : modelsOf(TrainClientRegistry.getTrainProperties(trainId))) {
				if (FAVOURED.containsKey(model)) {
					FAVOURED.put(model, USAGE_SCORES.get(trainId));
				}
			}
		}
		final StringBuilder most = new StringBuilder();
		for (int i = 0; i < Math.min(5, trainIds.size()); i++) {
			most.append(i == 0 ? "" : ", ").append(trainIds.get(i)).append(" x").append(trainsNow.get(i));
		}
		LOGGER.info("On-demand: the server's {} train type(s) by use received; most used: {}", trainIds.size(), most);
	}

	/** A model of the current reload's trains (made in the reload, on the render thread) */
	public static void register(LazyTrainModel model) {
		MODELS.add(model);
	}

	/** Builds a model in the background (called once per model, by {@link LazyTrainModel#request}) */
	public static void build(LazyTrainModel model) {
		final int buildGeneration = generation;
		BUILDING.incrementAndGet();
		WORKER.submit(() -> {
			if (buildGeneration != generation) {
				model.reset();
				BUILDING.decrementAndGet();
				return;
			}
			final long startMillis = System.currentTimeMillis();
			final List<SowcerModelAgent> agents = UploadCapture.begin();
			final int errorsBefore = cn.zbx1425.mtrsteamloco.render.integration.MtrModelRegistryUtil.loadingErrorList.size();
			try {
				final ModelTrainBase built = model.build();
				// ANTE's loader logs what goes wrong and carries on with an empty model (a pack closed by a reload going on,
				// say): that is a failed build, not a model to draw nothing with
				if (cn.zbx1425.mtrsteamloco.render.integration.MtrModelRegistryUtil.loadingErrorList.size() > errorsBefore) {
					throw new IllegalStateException("the model loader reported an error");
				}
				final List<Supplier<ModelCluster>> prepared = new ArrayList<>(agents.size());
				for (final SowcerModelAgent agent : agents) {
					prepared.add(agent.prepareUpload());
				}
				BUILT.add(new Built(model, built, agents, prepared, buildGeneration, System.currentTimeMillis() - startMillis));
			} catch (Throwable e) {
				LOGGER.error("On-demand: building train model {} failed", model.name, e);
				model.setFailed();
				BUILDING.decrementAndGet();
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
					BUILDING.decrementAndGet();
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
		if (now - lastPreloadCheck > PRELOAD_CHECK_NANOS) {
			lastPreloadCheck = now;
			preloadMostUsed();
		}
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
		final double heap = heapUsed();
		final boolean pressure = heap > HEAP_PRESSURE;
		final long idle = pressure ? IDLE_UNDER_PRESSURE_NANOS : IDLE_NANOS;
		int released = 0;
		for (final LazyTrainModel model : MODELS) {
			if (!FAVOURED.containsKey(model) && model.getState() == LazyTrainModel.READY && now - model.getLastUsedNanos() > idle) {
				release(model);
				released++;
			}
		}
		// The much used ones only when the heap is nearly full, the least used first, a few at a time
		if (heap > KEEP_FAVOURED_UNTIL_HEAP) {
			final List<java.util.Map.Entry<LazyTrainModel, Double>> favoured = new ArrayList<>(FAVOURED.entrySet());
			favoured.sort(java.util.Map.Entry.comparingByValue());
			int favouredReleased = 0;
			for (final java.util.Map.Entry<LazyTrainModel, Double> entry : favoured) {
				final LazyTrainModel model = entry.getKey();
				if (favouredReleased < FAVOURED_RELEASED_AT_ONCE && model.getState() == LazyTrainModel.READY && now - model.getLastUsedNanos() > FAVOURED_IDLE_NANOS) {
					release(model);
					FAVOURED.remove(model);
					favouredReleased++;
					released++;
				}
			}
		}
		if (released > 0) {
			cn.zbx1425.sowcerext.model.CompactArrays.clear();
			releasedCount += released;
			builtCount -= released;
			LOGGER.info("On-demand: let {} unused train model(s) go{} ({} let go so far)", released, pressure ? " (heap filling up)" : "", releasedCount);
		}
	}

	private static void release(LazyTrainModel model) {
		for (final Object upload : model.release()) {
			if (upload instanceof SowcerModelAgent) {
				final SowcerModelAgent agent = (SowcerModelAgent) upload;
				cn.zbx1425.mtrsteamloco.MainClient.modelManager.release(agent.uploadedModel);
				agent.uploadedModel = null;
			}
		}
	}

	/**
	 * How full the heap is with what is alive: the heap pools as the last collection of each left them (what is used now
	 * counts garbage not collected yet, which would stop building ahead too soon), or as used now if that isn't known
	 */
	private static double heapUsed() {
		final Runtime runtime = Runtime.getRuntime();
		long afterCollection = 0;
		boolean known = false;
		for (final java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.getCollectionUsage() != null) {
				afterCollection += pool.getCollectionUsage().getUsed();
				known = true;
			}
		}
		final long used = runtime.totalMemory() - runtime.freeMemory();
		return (double) (known ? Math.min(used, afterCollection) : used) / runtime.maxMemory();
	}

	/**
	 * Builds the next most used type's models ahead, one type at a time, while nothing else is being built and the heap
	 * is less than {@link #PRELOAD_HEAP} full: how many get built depends on the memory left.
	 */
	private static void preloadMostUsed() {
		if (usageOrder.isEmpty() || BUILDING.get() > 0 || heapUsed() >= PRELOAD_HEAP) {
			return;
		}
		for (final String trainId : usageOrder) {
			final List<LazyTrainModel> models = modelsOf(TrainClientRegistry.getTrainProperties(trainId));
			if (models.isEmpty()) {
				continue;
			}
			final double score = USAGE_SCORES.getOrDefault(trainId, 0D);
			boolean requested = false;
			for (final LazyTrainModel model : models) {
				if (model.getState() == LazyTrainModel.NEW) {
					model.request();
					requested = true;
				}
				if (model.getState() != LazyTrainModel.FAILED) {
					FAVOURED.merge(model, score, Math::max);
				}
			}
			if (requested) {
				preloadedCount++;
				LOGGER.info("On-demand: building {} ahead, the server's {} most used type ({} built ahead so far, heap {}% full)", trainId, ordinal(usageOrder.indexOf(trainId) + 1), preloadedCount, Math.round(heapUsed() * 100));
				return;
			}
		}
	}

	private static String ordinal(int number) {
		return number + (number % 100 >= 11 && number % 100 <= 13 ? "th" : number % 10 == 1 ? "st" : number % 10 == 2 ? "nd" : number % 10 == 3 ? "rd" : "th");
	}

	/** The on-demand models of a train type: its cars' and its bogies' */
	private static List<LazyTrainModel> modelsOf(TrainProperties properties) {
		final List<LazyTrainModel> models = new ArrayList<>();
		if (properties == null) {
			return models;
		}
		// A scripted train's renderer wraps the one with the model
		Object renderer = properties.renderer;
		while (renderer instanceof cn.zbx1425.mtrsteamloco.scripting.train.ScriptedTrainRenderer) {
			renderer = ((cn.zbx1425.mtrsteamloco.scripting.train.ScriptedTrainRenderer) renderer).baseRenderer;
		}
		if (renderer instanceof JonModelTrainRenderer && ((JonModelTrainRenderer) renderer).model instanceof LazyTrainModel) {
			models.add((LazyTrainModel) ((JonModelTrainRenderer) renderer).model);
		}
		for (final CustomBogie bogie : new CustomBogie[]{properties.bogie1, properties.bogie2}) {
			if (bogie != null && bogie.model instanceof LazyTrainModel && !models.contains(bogie.model)) {
				models.add((LazyTrainModel) bogie.model);
			}
		}
		return models;
	}

	/** Asks for the models of a train type: its cars' and its bogies' */
	public static void request(TrainProperties properties) {
		for (final LazyTrainModel model : modelsOf(properties)) {
			LazyTrainModel.resolve(model);
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
