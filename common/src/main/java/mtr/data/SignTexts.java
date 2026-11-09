package mtr.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The words on railway signs and station name signs in Traditional Chinese, English and Japanese, all three on every sign
 * whatever language the player's game is in (2026-11-09). Each is one part of a text split by {@code |}, the way the signs
 * draw a line for each part.
 * <p>
 * A name of a station or line gives its own parts: its first part in Chinese characters is the Chinese name, its first in
 * other letters the English name, and its second in Chinese characters or kana the Japanese name, which is the Chinese one
 * where it has none. A name with no part in Chinese characters or kana is shown in the English line only, and one with no
 * part in other letters without the English line.
 */
public final class SignTexts {

	private static final Map<String, String[]> TEXTS = new HashMap<>();

	static {
		put("airport", "機場", "Airport", "空港");
		put("airport_arrivals", "抵港", "Arrivals", "到着");
		put("airport_departures", "離港", "Departures", "出発");
		put("airport_express", "往機場列車", "Trains to Airport", "空港行き列車");
		put("airport_express_city", "往市區列車", "Trains to City", "市内行き列車");
		put("airport_transfer", "轉機", "Transfer", "乗り継ぎ");
		put("baggage_claim", "提取行李", "Baggage Claim", "手荷物受取所");
		put("boat", "渡輪", "Ferries", "フェリー");
		put("cable_car", "纜車", "Cable Cars", "ロープウェイ");
		put("check_in_passengers", "登機乘客", "Check-in Passengers", "搭乗手続きのお客様");
		put("cross", "不准進入", "No Entry", "進入禁止");
		put("customer_service_centre", "客務中心", "Customer Service Centre", "お客さまセンター");
		put("emergency_exit", "緊急出口", "Emergency Exit", "非常口");
		put("escalator_to_concourse_down", "直落大堂", "To Concourse Only", "コンコース直通");
		put("escalator_to_concourse_up", "直上大堂", "To Concourse Only", "コンコース直通");
		put("female", "女洗手間", "Female Toilet", "女性用トイレ");
		put("in_town_check_in", "市區預辦登機", "In-town Check-in", "市内チェックイン");
		put("lift_1", "升降機", "Lift", "エレベーター");
		put("lift_2", "升降機", "Lift", "エレベーター");
		put("light_rail_1", "輕鐵", "Light Rail", "ライトレール");
		put("light_rail_2", "輕鐵", "Light Rail", "ライトレール");
		put("light_rail_3", "輕鐵", "Light Rail", "ライトレール");
		put("light_rail_4", "輕鐵", "Light Rail", "ライトレール");
		put("logo", "我的世界鐵路", "Minecraft Transit Railway", "マインクラフト鉄道");
		put("male", "男洗手間", "Male Toilet", "男性用トイレ");
		put("platform", "月台", "Platform", "のりば");
		put("sp1900", "列車", "Trains", "列車");
		put("station", "車站", "Station", "駅");
		put("tickets", "車票", "Tickets", "きっぷ");
		put("toilets", "洗手間", "Toilets", "トイレ");
		put("train", "列車", "Trains", "列車");
		put("train_old", "列車", "Trains", "列車");
		put("wheelchair", "洗手間", "Toilets", "多機能トイレ");
		put("wifi", "免費Wi-Fi熱點", "Free Wi-Fi Hotspot", "無料Wi-Fiスポット");
		put("xrl_1", "高速鐵路", "High Speed Rail", "高速鉄道");
		put("xrl_2", "高速鐵路", "High Speed Rail", "高速鉄道");
		put("yellow_head_1", "火車", "Trains", "列車");
		put("yellow_head_2", "火車", "Trains", "列車");
	}

	private static final String[] STATION = {"%s站", "%s Station", "%s駅"};
	private static final String[] TO = {"往%s", "To %s", "%s方面"};

	private SignTexts() {
	}

	private static void put(String name, String chinese, String english, String japanese) {
		TEXTS.put(name, new String[]{chinese, english, japanese});
	}

	/**
	 * @param name the sign's text by the name of its translation, as {@code sign.mtr.<name>}
	 * @return its words in the three languages, or null if it has none here
	 */
	public static String forSign(String name) {
		final String[] texts = TEXTS.get(name);
		return texts == null ? null : String.join("|", texts);
	}

	/**
	 * @return a station's name as a station on a sign, such as 中環站|Central Station|中環駅
	 */
	public static String station(String name) {
		return fill(STATION, name);
	}

	/**
	 * @return where a train goes, such as 往中環|To Central|中環方面
	 */
	public static String to(String destinations) {
		return fill(TO, destinations);
	}

	/**
	 * @return the name's Chinese, English and Japanese parts, as described for the class
	 */
	public static String[] nameParts(String name) {
		final List<String> cjk = new ArrayList<>();
		final List<String> other = new ArrayList<>();
		for (final String part : name.split("\\|")) {
			if (part.trim().isEmpty()) {
				continue;
			}
			(IGui.isCjk(part) ? cjk : other).add(part.trim());
		}
		final String chinese = cjk.isEmpty() ? other.isEmpty() ? name : other.get(0) : cjk.get(0);
		final String english = other.isEmpty() ? chinese : other.get(0);
		final String japanese = cjk.size() > 1 ? cjk.get(1) : chinese;
		return new String[]{chinese, english, japanese};
	}

	/**
	 * @return a name, or names merged ({@link IGui#mergeStations}, which puts all the parts in Chinese characters first), as
	 * its Chinese, English and Japanese parts in that order
	 */
	public static String ordered(String text) {
		return text == null || text.isEmpty() ? "" : String.join("|", nameParts(text));
	}

	private static String fill(String[] templates, String name) {
		if (name == null || name.isEmpty()) {
			return "";
		}
		final String[] parts = nameParts(name);
		// A name with no part in Chinese characters or kana is only in the English line, rather than "Airport站"
		if (!IGui.isCjk(name)) {
			return String.format(templates[1], parts[1]);
		}
		// And one with no part in other letters has no English line
		if (java.util.Arrays.stream(name.split("\\|")).allMatch(part -> part.trim().isEmpty() || IGui.isCjk(part))) {
			return String.format(templates[0], parts[0]) + "|" + String.format(templates[2], parts[2]);
		}
		return String.format(templates[0], parts[0]) + "|" + String.format(templates[1], parts[1]) + "|" + String.format(templates[2], parts[2]);
	}
}
