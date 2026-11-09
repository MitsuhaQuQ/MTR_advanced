package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Railway signs and station name signs in Traditional Chinese, English and Japanese (2026-11-09).
 */
class SignTextsTest {

	@Test
	void aSignsWordsAreInTheThreeLanguages() {
		assertEquals("月台|Platform|のりば", SignTexts.forSign("platform"));
		assertEquals("洗手間|Toilets|トイレ", SignTexts.forSign("toilets"));
		assertNull(SignTexts.forSign("no_such_sign"));
	}

	@Test
	void aStationsNameHasItsJapanesePartOrItsChineseOne() {
		assertEquals("中環站|Central Station|中環駅", SignTexts.station("中環|Central"));
		assertEquals("新宿站|Shinjuku Station|しんじゅく駅", SignTexts.station("新宿|Shinjuku|しんじゅく"));
		// The parts in any order
		assertEquals("往東京|To Tokyo|とうきょう方面", SignTexts.to("Tokyo|東京|とうきょう"));
		// A name in English only: the English line alone
		assertEquals("Airport Station", SignTexts.station("Airport"));
		// One in Chinese only: no English line
		assertEquals("機場站|機場駅", SignTexts.station("機場"));
		assertEquals("", SignTexts.station(""));
	}

	@Test
	void namesMergedAreOrderedChineseEnglishJapanese() {
		// mergeStations puts every part in Chinese characters first; the sign shows Chinese, English, Japanese
		assertEquals(Arrays.asList("中環站", "Central Station", "中環駅"), Arrays.asList(SignTexts.ordered("中環站|中環駅|Central Station").split("\\|")));
		assertEquals("", SignTexts.ordered(""));
	}
}
