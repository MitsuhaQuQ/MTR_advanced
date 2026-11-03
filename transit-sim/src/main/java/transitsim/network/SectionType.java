package transitsim.network;

/**
 * What a section of the network is for. Platforms, sidings and turn back rails are sections of their own.
 */
public enum SectionType {
	NORMAL, PLATFORM, SIDING, TURN_BACK;

	/**
	 * Reads a saved type. Sections saved before the network moved into the component have the name of the host's rail
	 * type, which is a platform, siding or turn back rail by the same name and an ordinary rail otherwise.
	 */
	public static SectionType fromName(String name) {
		for (final SectionType type : values()) {
			if (type.name().equals(name)) {
				return type;
			}
		}
		return NORMAL;
	}
}
