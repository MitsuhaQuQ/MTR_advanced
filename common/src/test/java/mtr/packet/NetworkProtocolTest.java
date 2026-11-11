package mtr.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A client and its server need the same protocol only, the first two numbers of the version (0.66.0).
 */
class NetworkProtocolTest {

	@Test
	void theProtocolIsTheVersionUpToItsSecondNumber() {
		assertEquals("MTM_Logic 0.66", IPacket.getProtocol("MTM_Logic 0.66.0"));
		assertEquals("MTM_Logic 0.66", IPacket.getProtocol("MTM_Logic 0.66.12"));
		assertEquals(IPacket.getProtocol("MTM_Logic 0.66.1"), IPacket.getProtocol("MTM_Logic 0.66.7"));
		assertNotEquals(IPacket.getProtocol("MTM_Logic 0.65.33"), IPacket.getProtocol("MTM_Logic 0.66.0"));
		assertEquals("MTM_Logic 1", IPacket.getProtocol("MTM_Logic 1"));
		assertEquals("", IPacket.getProtocol(null));
		assertTrue(IPacket.getNetworkVersion().startsWith(IPacket.getProtocol(IPacket.getNetworkVersion())));
	}
}
