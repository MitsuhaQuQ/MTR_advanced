package cn.zbx1425.mtrsteamloco.data;

import java.util.HashMap;
import java.util.Map;

/**
 * What ANTE keeps for a train, held by its {@link mtr.data.TrainIdentity} so it stays with the train when the train goes on
 * as a train of another siding of its pool (R1).
 */
public final class TrainAnteState {

	public static final String KEY = "mtrsteamloco";

	public Map<String, String> customConfigs = new HashMap<>();
	public Map<String, ConfigResponder> configResponders = new HashMap<>();
	public boolean configsChanged;
}
