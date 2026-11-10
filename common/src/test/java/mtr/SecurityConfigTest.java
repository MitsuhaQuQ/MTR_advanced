package mtr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityConfigTest {

	@TempDir
	Path folder;

	@AfterEach
	void restoreDefaults() {
		SecurityConfig.audioMaxSeconds = SecurityConfig.DEFAULT_AUDIO_MAX_SECONDS;
	}

	@Test
	void missingAudioLimitDefaultsToFiveMinutes() throws Exception {
		final Path config = folder.resolve("mtr_security.json");
		Files.write(config, "{}".getBytes(StandardCharsets.UTF_8));

		SecurityConfig.load(config);

		assertEquals(300, SecurityConfig.audioMaxSeconds);
	}

	@Test
	void legacyOneMinuteDefaultMigratesToFiveMinutes() throws Exception {
		final Path config = folder.resolve("mtr_security.json");
		Files.write(config, "{\"audioMaxSeconds\": 60}".getBytes(StandardCharsets.UTF_8));

		SecurityConfig.load(config);

		assertEquals(300, SecurityConfig.audioMaxSeconds);
	}

	@Test
	void explicitShorterAudioLimitIsPreserved() throws Exception {
		final Path config = folder.resolve("mtr_security.json");
		Files.write(config, "{\"audioMaxSeconds\": 90}".getBytes(StandardCharsets.UTF_8));

		SecurityConfig.load(config);

		assertEquals(90, SecurityConfig.audioMaxSeconds);
	}
}
