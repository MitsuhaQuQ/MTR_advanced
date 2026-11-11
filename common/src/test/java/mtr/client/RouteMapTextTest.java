package mtr.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteMapTextTest {
	@Test
	void removesTheRepeatedThirdLineAndConvertsSimplifiedChinese() {
		assertEquals("順時針環線 下一站維拉吉|Clockwise loop, next Village", RouteMapText.bilingual("顺时针环线 下一站维拉吉|Clockwise loop, next Village|顺时针环线 下一站维拉吉"));
		assertEquals("新宿|Shinjuku", RouteMapText.bilingual("新宿|Shinjuku|しんじゅく"));
		assertEquals("東京|Tokyo", RouteMapText.bilingual("Tokyo|东京|東京"));
	}

	@Test
	void loopAndDestinationLabelsHaveOnlyTheAvailableLanguages() {
		assertEquals("順時針環線 下一站維拉吉|Clockwise loop, next Village", RouteMapText.loopNext("clockwise", "维拉吉|Village"));
		assertEquals("逆時針環線 下一站機場|Anticlockwise loop, next Airport", RouteMapText.loopNext("anticlockwise", "机场|Airport"));
		assertEquals("Loop, next Hell", RouteMapText.loopNext("loop", "Hell"));
		assertEquals("往機場|To Airport", RouteMapText.to("机场|Airport|空港"));
		assertEquals("往機場", RouteMapText.to("机场"));
		assertEquals("To Hell", RouteMapText.to("Hell"));
		assertEquals("", RouteMapText.bilingual(null));
	}
}
