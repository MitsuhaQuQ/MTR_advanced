package mtr.image;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageSearchTest {

	private static final List<String> NAMES = Arrays.asList("Station Map.png", "station_sign_east.gif", "广告 地铁.png", "Logo");

	@Test
	void aBlankQueryKeepsEveryImageInOrder() {
		assertEquals(Arrays.asList(0, 1, 2, 3), ImageSearch.filter(NAMES, ""));
		assertEquals(Arrays.asList(0, 1, 2, 3), ImageSearch.filter(NAMES, "   "));
		assertEquals(Arrays.asList(0, 1, 2, 3), ImageSearch.filter(NAMES, null));
	}

	@Test
	void everyWordMatchesSomewhereApartFromCase() {
		assertEquals(Arrays.asList(0, 1), ImageSearch.filter(NAMES, "STATION"));
		assertEquals(Collections.singletonList(1), ImageSearch.filter(NAMES, "sign station"));
		assertEquals(Collections.singletonList(2), ImageSearch.filter(NAMES, "地铁 广告"));
		assertEquals(Collections.singletonList(1), ImageSearch.filter(NAMES, ".gif"));
		assertTrue(ImageSearch.filter(NAMES, "west").isEmpty());
		assertTrue(ImageSearch.matches("Original painting", "orig"));
		assertFalse(ImageSearch.matches(null, "x"));
	}
}
