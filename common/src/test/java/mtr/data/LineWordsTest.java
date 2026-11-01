package mtr.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineWordsTest {

	@TempDir
	Path folder;

	@AfterEach
	void restoreDefaultWords() throws IOException {
		final Path path = Files.createTempDirectory("mtr_line_words").resolve(LineWords.FILE_NAME);
		LineWords.load(path);
		Files.deleteIfExists(path);
		Files.deleteIfExists(path.getParent());
		LineWords.load(null);
	}

	private Path write(String text) throws IOException {
		final Path path = folder.resolve(LineWords.FILE_NAME);
		Files.write(path, text.getBytes(StandardCharsets.UTF_8));
		return path;
	}

	@Test
	void defaultWordsComeFromTheFileInTheJar() {
		assertEquals("1号线", LineWords.removeFromEnds("1号线 快速 上行"));
	}

	@Test
	void missingFileIsCreatedFromTheDefaults() throws IOException {
		final Path path = folder.resolve("sub").resolve(LineWords.FILE_NAME);
		LineWords.load(path);

		assertTrue(Files.exists(path));
		assertTrue(new String(Files.readAllBytes(path), StandardCharsets.UTF_8).contains("serviceWords"));
		assertEquals("1号线", LineWords.removeFromEnds("1号线 快速"));
	}

	@Test
	void wordsFromTheFileReplaceTheDefaults() throws IOException {
		LineWords.load(write("{\"directionWords\": [\"东行\"], \"serviceWords\": [\"特别快速\"]}"));

		assertEquals("1号线", LineWords.removeFromEnds("1号线 特别快速 东行"));
		assertEquals("1号线 上行", LineWords.removeFromEnds("1号线 上行"));
	}

	@Test
	void reloadPicksUpChanges() throws IOException {
		LineWords.load(write("{\"serviceWords\": [\"甲\"]}"));
		assertEquals("1号线 乙", LineWords.removeFromEnds("1号线 乙"));

		write("{\"serviceWords\": [\"乙\"]}");
		LineWords.reload();
		assertEquals("1号线", LineWords.removeFromEnds("1号线 乙"));
	}

	@Test
	void brokenFileKeepsTheWordsInUse() throws IOException {
		LineWords.load(write("{\"serviceWords\": [\"甲\"]}"));
		write("not json");
		LineWords.reload();

		assertEquals("1号线", LineWords.removeFromEnds("1号线 甲"));
	}

	@Test
	void noWordsLeavesNamesAlone() throws IOException {
		LineWords.load(write("{}"));

		assertEquals("1号线 上行", LineWords.removeFromEnds("1号线 上行"));
	}

	@Test
	void englishWordsAreWholeWordsAndIgnoreCase() throws IOException {
		LineWords.load(write("{\"serviceWords\": [\"Express\"]}"));

		assertEquals("Line 1", LineWords.removeFromEnds("Line 1 EXPRESS"));
		assertEquals("Expressway", LineWords.removeFromEnds("Expressway"));
	}

	@Test
	void specialCharactersInWordsAreLiteral() throws IOException {
		LineWords.load(write("{\"serviceWords\": [\"A+\"]}"));

		assertEquals("1号线", LineWords.removeFromEnds("1号线 A+"));
		assertEquals("1号线 AA", LineWords.removeFromEnds("1号线 AA"));
	}
}
