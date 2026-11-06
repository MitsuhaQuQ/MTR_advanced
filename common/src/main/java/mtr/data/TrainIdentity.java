package mtr.data;

import net.minecraft.world.SimpleContainer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What makes a train the same train wherever it goes (R1, after P2-C): its riders, its cargo and what other parts keep for
 * it, such as ANTE's settings. A train going on as a train of another siding of its pool is a new {@link Train} for that
 * siding, with its run state made afresh, holding the same identity.
 */
public final class TrainIdentity {

	public final Set<UUID> ridingEntities = new HashSet<>();
	/**
	 * Its cargo, a slot a car; null on the client.
	 */
	SimpleContainer inventory;
	private final Map<String, Object> attachments = new HashMap<>();

	/**
	 * @return what another part keeps for the train under a key, made the first time it is asked for
	 */
	@SuppressWarnings("unchecked")
	public <T> T getAttachment(String key, Supplier<T> make) {
		return (T) attachments.computeIfAbsent(key, newKey -> make.get());
	}
}
