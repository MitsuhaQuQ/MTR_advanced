package cn.zbx1425.mtrsteamloco;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Looks through ANTE's mixins, as source, for what quietly replaces MTR's own code, which compiles and loads fine and only
 * shows when the replaced code is reached:
 * <ul>
 *     <li>a shadowed field given a value: Mixin merges the initialiser into the target's constructors, so the target's own
 *     field is replaced (the train positions made an empty list, 2026-11-05) — never allowed;</li>
 *     <li>a method with the name and number of parameters of one of the target's, with {@code @Override} or none, or an
 *     {@code @Overwrite}: it takes the target's method's place whole;</li>
 *     <li>an injection at the head that cancels at its top level: the target's method never runs.</li>
 * </ul>
 * The last two are allowed where looked at and listed in {@link #ALLOWED} with why; a new one, or one listed and gone, fails
 * the build, so a change on either side is looked at again.
 */
class MixinLintTest {

	private static final Path SOURCES = Paths.get("src/main/java");
	private static final Path MIXINS = SOURCES.resolve("cn/zbx1425/mtrsteamloco/mixin");

	private static final Map<String, String> ALLOWED = new TreeMap<>();

	static {
		ALLOWED.put("BlockEntityRenderDispatcherMixin.cullVanillaDecoration", "only skips confirmed off-screen vanilla decoration renderers in the experimental main view");
		ALLOWED.put("RailwayDataMixin.simulateTrains", "ANTE's rails are found near players by their own shape and the view distance; then MTR's own tick runs");
		ALLOWED.put("RenderTrainsMixin.getTextureLocation", "the seat entity's texture, which MTR leaves empty");
		ALLOWED.put("RenderTrainsMixin.onRenderRailStandard", "rails ANTE draws from its chunk buffers (3D rails, or MTR's flat rails as a model) are not drawn again quad by quad; MTR's own drawing stays for the rest and while a rail tool is held");
		ALLOWED.put("TrainClientMixin.handlePositions", "MTR's own with ANTE's roll of the cars and riders added");
		ALLOWED.put("TrainClientMixin.simulateCar", "MTR's own with the roll of the cars where they join");
		ALLOWED.put("CustomResourcesMixin.readResource", "ANTE's resource reading");
		ALLOWED.put("FQTruffleLanguage.isThreadAccessAllowed", "GraalJS in the mod's class loader");
		ALLOWED.put("RailMixin.render", "rails drawn with ANTE's roll");
		ALLOWED.put("RailMixin.onGetPositionY", "ANTE's vertical curves, until MTR has its own (curve editing)");
		ALLOWED.put("RailTypeMixin.valueOf", "ANTE's P speed rail types");
		ALLOWED.put("TrainMixin.convertMaxManualSpeed", "the manual speed limit for ANTE's P speed rail types");
		ALLOWED.put("VehicleRidingClientMixin.onRenderPlayerAndGetOffset", "riders placed with ANTE's roll");
		ALLOWED.put("VehicleRidingClientMixin.setOffsets", "riders placed with ANTE's roll");
		ALLOWED.put("VehicleRidingClientMixin.getValueFromPercentage", "a private helper of the same name, used only by ANTE's own code");
		ALLOWED.put("FQHotSpotTruffleRuntimeAccess.onCreateRuntime", "GraalJS's runtime in the mod's class loader");
		ALLOWED.put("GLFWMixin.glfwCreateWindow", "the window ANTE's renderer needs");
		ALLOWED.put("KeyboardHandlerMixin.handleDebugKeysHead", "F3+5 reloads ANTE's scripts when the script debug overlay is on; Minecraft's own debug keys otherwise");
		ALLOWED.put("PackMixin.getCompatibility", "MTR resource packs count as compatible with any pack format; Minecraft's own check for other packs");
		ALLOWED.put("TrainMixin.onScanDoors", "MTR's door scan with ANTE's decorations and other mods' platforms as platforms; it scans on after a door opens, where MTR's stops");
	}

	private static final Pattern MIXIN_TARGET = Pattern.compile("@Mixin\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*([\\w.]+)\\.class");
	private static final Pattern METHOD = Pattern.compile("(?m)^[ \\t]*((?:@[\\w.]+(?:\\((?:[^()]|\\([^()]*\\))*\\))?\\s*)*)((?:(?:public|protected|private|static|final|abstract|synchronized)\\s+)*)[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [\\w., ]+)?\\s*\\{");
	private static final Pattern TARGET_METHOD = Pattern.compile("(?m)^[ \\t]*(?:(?:public|protected|private|static|final|abstract|synchronized)\\s+)*[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [\\w., ]+)?\\s*[{;]");
	private static final Set<String> NOT_REPLACING = new HashSet<>(Arrays.asList("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant", "Shadow", "Invoker", "Accessor", "Unique", "WrapOperation", "WrapWithCondition"));

	@Test
	void mixinsDontQuietlyReplaceMtrsOwnCode() throws IOException {
		final Map<String, Path> sources;
		try (Stream<Path> files = Files.walk(SOURCES)) {
			sources = files.filter(path -> path.toString().endsWith(".java") && !path.startsWith(MIXINS)).collect(Collectors.toMap(path -> path.getFileName().toString().replace(".java", ""), path -> path, (a, b) -> a));
		}
		final List<String> neverAllowed = new ArrayList<>();
		final Set<String> found = new TreeSet<>();
		try (Stream<Path> files = Files.list(MIXINS)) {
			for (final Path path : files.filter(file -> file.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
				final String mixin = path.getFileName().toString().replace(".java", "");
				final String source = stripComments(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
				final Matcher target = MIXIN_TARGET.matcher(source);
				if (!target.find()) {
					continue;
				}
				neverAllowed.addAll(findShadowInitialisers(mixin, source));
				final Path targetPath = findTarget(target.group(1), source, sources);
				if (targetPath != null) {
					found.addAll(findReplacements(mixin, source, stripComments(new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8))));
				}
				found.addAll(findCancellingInjections(mixin, source));
			}
		}

		final List<String> newOnes = found.stream().filter(key -> !ALLOWED.containsKey(key)).collect(Collectors.toList());
		final List<String> gone = ALLOWED.keySet().stream().filter(key -> !found.contains(key)).collect(Collectors.toList());
		final StringBuilder message = new StringBuilder();
		if (!neverAllowed.isEmpty()) {
			message.append("Shadowed fields given a value, which replaces the target's own field: ").append(neverAllowed).append('\n');
		}
		if (!newOnes.isEmpty()) {
			message.append("Mixin code replacing MTR's own whole; check it doesn't hide MTR's changes, then list it in ALLOWED: ").append(newOnes).append('\n');
		}
		if (!gone.isEmpty()) {
			message.append("Listed in ALLOWED but gone, take it out: ").append(gone).append('\n');
		}
		assertTrue(message.length() == 0, message.toString());
	}

	private static List<String> findShadowInitialisers(String mixin, String source) {
		final List<String> fields = new ArrayList<>();
		int index = 0;
		while ((index = source.indexOf("@Shadow", index)) >= 0) {
			final int end = firstOf(source, index, ';', '{');
			if (end >= 0 && source.charAt(end) == ';') {
				// What follows the annotations, which have their own "=" inside their brackets
				final String declaration = source.substring(index, end).replaceAll("@[\\w.]+(\\((?:[^()]|\\([^()]*\\))*\\))?", " ");
				if (declaration.contains("=") && !declaration.contains("(")) {
					fields.add(mixin + ": " + declaration.trim().replaceAll("\\s+", " "));
				}
			}
			index += "@Shadow".length();
		}
		return fields;
	}

	private static Set<String> findReplacements(String mixin, String source, String targetSource) {
		final Set<String> targetMethods = new HashSet<>();
		final Matcher targetMatcher = TARGET_METHOD.matcher(targetSource);
		while (targetMatcher.find()) {
			targetMethods.add(targetMatcher.group(1) + "/" + countParameters(targetMatcher.group(2)));
		}
		final Set<String> replacements = new TreeSet<>();
		final Matcher matcher = METHOD.matcher(source);
		while (matcher.find()) {
			final String annotations = matcher.group(1);
			final String name = matcher.group(3);
			if (Arrays.asList("if", "for", "while", "switch", "catch", "synchronized", "return", "new").contains(name)) {
				continue;
			}
			final boolean overwrite = annotations.contains("@Overwrite");
			final boolean injecting = NOT_REPLACING.stream().anyMatch(annotation -> annotations.contains("@" + annotation));
			if (overwrite || !injecting && targetMethods.contains(name + "/" + countParameters(matcher.group(4)))) {
				replacements.add(mixin + "." + name);
			}
		}
		return replacements;
	}

	private static Set<String> findCancellingInjections(String mixin, String source) {
		final Set<String> cancelling = new TreeSet<>();
		final Matcher matcher = METHOD.matcher(source);
		while (matcher.find()) {
			final String annotations = matcher.group(1);
			if (!annotations.contains("@Inject") || !annotations.replaceAll("\\s", "").contains("@At(\"HEAD\")") || !annotations.replaceAll("\\s", "").contains("cancellable=true")) {
				continue;
			}
			// A cancel at the top level of the body, not inside an if, always happens
			final int bodyStart = matcher.end() - 1;
			int depth = 0;
			final StringBuilder topLevel = new StringBuilder();
			for (int i = bodyStart; i < source.length(); i++) {
				final char c = source.charAt(i);
				if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
					if (depth == 0) {
						break;
					}
				} else if (depth == 1) {
					topLevel.append(c);
				}
			}
			// A cancel as a statement of its own, not one an if or a loop guards; or, anywhere, in a body long enough to be the
			// target's work done another way, while a short guard such as "if (skip) ci.cancel();" is left
			final String body = source.substring(bodyStart, findBodyEnd(source, bodyStart));
			final boolean cancels = Pattern.compile("\\b\\w+\\.(cancel|setReturnValue)\\(").matcher(body).find();
			boolean unconditional = false;
			for (final String statement : topLevel.toString().split(";")) {
				if (Pattern.compile("^\\s*\\w+\\.(cancel|setReturnValue)\\(").matcher(statement).find()) {
					unconditional = true;
					break;
				}
			}
			if (cancels &&(unconditional || body.chars().filter(c -> c == ';').count() > LONG_BODY_STATEMENTS)) {
				cancelling.add(mixin + "." + matcher.group(3));
			}
		}
		return cancelling;
	}

	/**
	 * @return the target's source: by its full name where the mixin gives one, else by the mixin's import of it, else by its
	 * name alone; null for a class not in this source, as Minecraft's
	 */
	private static Path findTarget(String target, String source, Map<String, Path> sources) {
		String fullName = target;
		if (!target.contains(".")) {
			final Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+\\." + Pattern.quote(target) + ");").matcher(source);
			fullName = imported.find() ? imported.group(1) : null;
		}
		if (fullName != null) {
			final Path path = SOURCES.resolve(fullName.replace('.', '/') + ".java");
			return Files.exists(path) ? path : null;
		}
		return sources.get(target);
	}

	/**
	 * More statements than this in an injection that cancels make it the target's work done another way.
	 */
	private static final int LONG_BODY_STATEMENTS = 4;

	private static int findBodyEnd(String source, int bodyStart) {
		int depth = 0;
		for (int i = bodyStart; i < source.length(); i++) {
			if (source.charAt(i) == '{') {
				depth++;
			} else if (source.charAt(i) == '}') {
				depth--;
				if (depth == 0) {
					return i + 1;
				}
			}
		}
		return source.length();
	}

	private static int countParameters(String parameters) {
		final String trimmed = parameters.trim();
		if (trimmed.isEmpty()) {
			return 0;
		}
		int count = 1;
		int depth = 0;
		for (final char c : trimmed.toCharArray()) {
			if (c == '<') {
				depth++;
			} else if (c == '>') {
				depth--;
			} else if (c == ',' && depth == 0) {
				count++;
			}
		}
		return count;
	}

	private static int firstOf(String source, int from, char a, char b) {
		final int indexA = source.indexOf(a, from);
		final int indexB = source.indexOf(b, from);
		return indexA < 0 ? indexB : indexB < 0 ? indexA : Math.min(indexA, indexB);
	}

	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}
}
