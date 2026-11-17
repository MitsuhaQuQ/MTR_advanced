package mtr;

import mtr.entity.EntityBase;
import mtr.image.PaintingProtection;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What /kill leaves alone (the loaders' {@code KillCommandMixin}), so a {@code /kill @e} doesn't clear what this mod puts
 * in the world as entities:
 * <ul>
 * <li>paintings showing an imported picture (0.78.2, {@link PaintingProtection}), always;</li>
 * <li>MTR's own entities, the seats players ride trains on and the lift cars ({@link EntityBase}) (0.97.0, user
 * 2026-11-17), when other entities are taken too: a /kill naming only them, as {@code /kill @e[type=mtr:seat]}, still
 * kills them. They kill themselves when done with, so their own {@code kill()} isn't changed.</li>
 * </ul>
 */
public final class KillProtection {

	private KillProtection() {
	}

	/**
	 * @return the targets of /kill without what is kept, the same collection if nothing is
	 */
	public static <T> Collection<T> withoutKept(Collection<T> targets) {
		final Collection<T> withoutPictures = PaintingProtection.withoutProtected(targets);
		final boolean anyMtr = withoutPictures.stream().anyMatch(target -> target instanceof EntityBase);
		final boolean anyOther = withoutPictures.stream().anyMatch(target -> !(target instanceof EntityBase));
		if (!anyMtr || !anyOther) {
			return withoutPictures;
		}
		final List<T> kept = withoutPictures.stream().filter(target -> !(target instanceof EntityBase)).collect(Collectors.toList());
		return kept;
	}
}
