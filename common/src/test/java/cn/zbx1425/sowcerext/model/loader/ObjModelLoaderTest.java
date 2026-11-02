package cn.zbx1425.sowcerext.model.loader;

import cn.zbx1425.sowcerext.model.RawModel;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ObjModelLoaderTest {

	private static final ResourceLocation LOCATION = new ResourceLocation("mtr", "train/train.obj");
	private static final String PART = "part";

	private static RawModel preload() {
		final RawModel model = new RawModel();
		model.sourceLocation = LOCATION;
		final Map<String, RawModel> models = new HashMap<>();
		models.put(PART, model);
		ObjModelLoader.setPreloadedModels(Collections.singletonMap(LOCATION, models));
		return model;
	}

	private static RawModel load() throws IOException {
		return ObjModelLoader.loadModels(null, LOCATION, null).get(PART);
	}

	@AfterEach
	void clear() {
		ObjModelLoader.clearPreloadedModels();
	}

	@Test
	void everyUseGetsItsOwnCopy() throws IOException {
		final RawModel model = preload();
		ObjModelLoader.prepareCopies(LOCATION, 1);

		final RawModel first = load();
		final RawModel second = load();

		assertNotSame(model, first);
		assertNotSame(model, second);
		assertNotSame(first, second);
		assertEquals(LOCATION, first.sourceLocation);
		assertEquals(LOCATION, second.sourceLocation);
	}

	@Test
	void usesNobodyCountedStillGetCopiesWithoutParsingAgain() throws IOException {
		final RawModel model = preload();
		ObjModelLoader.prepareCopies(LOCATION, 2);

		// There is no resource manager here, so parsing the file would throw
		for (int i = 0; i < 5; i++) {
			assertNotSame(model, load());
		}
	}

	@Test
	void workingWithoutPreparedCopiesIsFine() throws IOException {
		final RawModel model = preload();

		assertNotSame(model, load());
	}

	@Test
	void preparingCopiesOfAFileThatIsNotLoadedDoesNothing() {
		ObjModelLoader.prepareCopies(LOCATION, 2);
	}

	@Test
	void callersCanChangeTheMapTheyAreGiven() throws IOException {
		preload();
		ObjModelLoader.prepareCopies(LOCATION, 1);

		ObjModelLoader.loadModels(null, LOCATION, null).clear();

		assertEquals(1, ObjModelLoader.loadModels(null, LOCATION, null).size());
	}
}
