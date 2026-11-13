package mtr.image;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Paintings showing an imported picture are kept from /kill (0.78.2, user 2026-11-14: /kill @e cleared every picture put
 * up in a world): the command leaves them out of what it kills. A player can still break one by hand, or clear its picture
 * with the brush first.
 */
public final class PaintingProtection {

	private PaintingProtection() {
	}

	/**
	 * @return whether this entity is a painting showing an imported picture
	 */
	public static boolean isProtected(Object entity) {
		if (!(entity instanceof PaintingImage)) {
			return false;
		}
		final String settings = ((PaintingImage) entity).mtr$getImageSettings();
		if (settings == null || settings.isEmpty()) {
			return false;
		}
		// The brush clearing the picture leaves settings with no image, which isn't kept
		try {
			return !PaintingSettings.parse(settings).image.isEmpty();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * @return the targets of /kill without the protected paintings, the same collection if there are none
	 */
	public static <T> Collection<T> withoutProtected(Collection<T> targets) {
		if (targets.stream().noneMatch(PaintingProtection::isProtected)) {
			return targets;
		}
		final List<T> kept = new ArrayList<>();
		for (final T target : targets) {
			if (!isProtected(target)) {
				kept.add(target);
			}
		}
		return kept;
	}
}
