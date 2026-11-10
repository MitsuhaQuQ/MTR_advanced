package mtr.media;

/** Shared ownership rule for audio and pictures; administrator status is checked by the server. */
public final class MediaPermissions {
	private MediaPermissions() { }
	public static boolean canDelete(String owner, String actor, boolean administrator) {
		return administrator || owner != null && owner.equals(actor);
	}
}
