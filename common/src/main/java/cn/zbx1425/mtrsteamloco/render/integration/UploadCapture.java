package cn.zbx1425.mtrsteamloco.render.integration;

import java.util.ArrayList;
import java.util.List;

/**
 * While a train model is built off the render thread (0.100.0, on-demand train models), the model agents made then keep
 * their models instead of uploading them (no GL there); the render thread uploads them later.
 */
public final class UploadCapture {

	private static final ThreadLocal<List<SowcerModelAgent>> CAPTURED = new ThreadLocal<>();

	private UploadCapture() {
	}

	/** Starts capturing on this thread; the list fills as agents are made */
	public static List<SowcerModelAgent> begin() {
		final List<SowcerModelAgent> agents = new ArrayList<>();
		CAPTURED.set(agents);
		return agents;
	}

	public static void end() {
		CAPTURED.remove();
	}

	public static boolean isActive() {
		return CAPTURED.get() != null;
	}

	static void add(SowcerModelAgent agent) {
		final List<SowcerModelAgent> agents = CAPTURED.get();
		if (agents != null) {
			agents.add(agent);
		}
	}
}
