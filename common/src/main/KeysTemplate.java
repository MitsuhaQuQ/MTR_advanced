package mtr;

public interface Keys {

	String PATREON_API_KEY = "@key@";
	String MOD_VERSION = "@version@";
	/**
	 * This fork's own version (MTM_Logic), which clients and servers must have the same of.
	 */
	String MTM_VERSION = "@mtm_version@";
	boolean LIFTS_ONLY = "@lifts@".equals("lifts");
	boolean TEST_SERVER = "@server@".equals("true");
}
