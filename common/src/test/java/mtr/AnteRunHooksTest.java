package mtr;

import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ANTE's run hooks are off unless the server's settings turn them on, and MTR's own way of telling rails near a player,
 * which the hooks fall back to, is the one it always had.
 */
class AnteRunHooksTest {

	@AfterEach
	void reset() {
		SecurityConfig.anteRunHooks = false;
	}

	@Test
	void theHooksAreOffWithoutTheSetting(@TempDir Path directory) throws Exception {
		final Path path = directory.resolve("mtr_security.json");
		Files.write(path, "{\"throatLocks\": true}".getBytes(StandardCharsets.UTF_8));
		SecurityConfig.anteRunHooks = true;
		SecurityConfig.load(path);
		assertFalse(SecurityConfig.anteRunHooks);
	}

	@Test
	void theSettingTurnsThemOn(@TempDir Path directory) throws Exception {
		final Path path = directory.resolve("mtr_security.json");
		Files.write(path, "{\"anteRunHooks\": true}".getBytes(StandardCharsets.UTF_8));
		SecurityConfig.load(path);
		assertTrue(SecurityConfig.anteRunHooks);
	}

	@Test
	void aNewSettingsFileHasThemOff(@TempDir Path directory) throws Exception {
		final Path path = directory.resolve("config").resolve("mtr_security.json");
		SecurityConfig.load(path);
		assertFalse(SecurityConfig.anteRunHooks);
		assertTrue(new String(Files.readAllBytes(path), StandardCharsets.UTF_8).contains("\"anteRunHooks\": false"));
	}

	@Test
	void aRailIsNearWithinTheBoxAroundItsEnds() {
		final BlockPos start = new BlockPos(0, 64, 0);
		final BlockPos end = new BlockPos(100, 64, 0);
		assertTrue(RailwayData.isRailNearInBox(null, start, end, new Vec3(50, 64, 120)));
		assertTrue(RailwayData.isRailNearInBox(null, start, end, new Vec3(-120, 64, 0)));
		assertFalse(RailwayData.isRailNearInBox(null, start, end, new Vec3(50, 64, 140)));
		assertFalse(RailwayData.isRailNearInBox(null, start, end, new Vec3(240, 64, 0)));
	}
}
