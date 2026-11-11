package mtr.audio;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Keeps a loop alive for previous listeners until they leave the world or the speaker stops. */
public final class SpeakerListeners {
	private final Set<UUID> listeners = new HashSet<>();

	public Set<UUID> update(Set<UUID> present, Set<UUID> audible, boolean refresh) {
		listeners.retainAll(present);
		final Set<UUID> notify = new HashSet<>();
		for (final UUID id : audible) {
			if (present.contains(id) && listeners.add(id)) notify.add(id);
		}
		if (refresh) notify.addAll(listeners);
		return notify;
	}

	public Set<UUID> clear() {
		final Set<UUID> previous = new HashSet<>(listeners);
		listeners.clear();
		return previous;
	}
}
