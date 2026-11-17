package mtr.escalator;

import mtr.mappings.PersistentStateMapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * The free escalators taken away in a dimension (0.100.0), kept with the dimension's saved data: an escalator's blocks
 * in chunks not loaded when it was taken away are taken away when their chunk loads ({@link FreeEscalators#check}),
 * so an escalator never comes back in part, even across a crash between chunks being saved. The oldest are forgotten
 * past {@link #LIMIT}; a block left after that still goes if its first block is found gone.
 */
public class FreeEscalatorRemovals extends PersistentStateMapper {

	public static final int LIMIT = 10000;
	private static final String NAME = "mtr_free_escalator_removals";
	private static final String KEY_REMOVED = "removed";

	private final LinkedHashSet<Long> removed = new LinkedHashSet<>();

	public FreeEscalatorRemovals() {
		super(NAME);
	}

	/** Null on a client */
	public static FreeEscalatorRemovals getInstance(Level world) {
		return getInstance(world, FreeEscalatorRemovals::new, NAME);
	}

	public boolean contains(long id) {
		return removed.contains(id);
	}

	public void add(long id) {
		if (id != 0 && removed.add(id)) {
			final Iterator<Long> iterator = removed.iterator();
			while (removed.size() > LIMIT && iterator.hasNext()) {
				iterator.next();
				iterator.remove();
			}
			setDirty();
		}
	}

	@Override
	public void load(CompoundTag compoundTag) {
		removed.clear();
		for (final long id : compoundTag.getLongArray(KEY_REMOVED)) {
			if (id != 0) {
				removed.add(id);
			}
		}
	}

	@Override
	public CompoundTag save(CompoundTag compoundTag) {
		final long[] ids = new long[removed.size()];
		int i = 0;
		for (final long id : removed) {
			ids[i++] = id;
		}
		compoundTag.putLongArray(KEY_REMOVED, ids);
		return compoundTag;
	}
}
