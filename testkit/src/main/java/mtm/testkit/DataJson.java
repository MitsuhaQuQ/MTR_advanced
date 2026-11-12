package mtm.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.data.SerializedDataBase;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * MTR data as JSON, through each object's own save format (msgpack), so the server's and the client's copies are written
 * the same way and can be compared field by field.
 */
public final class DataJson {

	private DataJson() {
	}

	public static JsonElement of(SerializedDataBase data) {
		try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
			packer.packMapHeader(data.messagePackLength());
			data.toMessagePack(packer);
			packer.flush();
			try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray())) {
				return JsonParser.parseString(unpacker.unpackValue().toJson());
			}
		} catch (Exception e) {
			final JsonObject error = new JsonObject();
			error.addProperty("error", e.toString());
			return error;
		}
	}

	/**
	 * @return the objects sorted by id, so two snapshots list them in the same order
	 */
	public static JsonArray of(Collection<? extends mtr.data.NameColorDataBase> dataSet) {
		final List<mtr.data.NameColorDataBase> sorted = new ArrayList<>(dataSet);
		sorted.sort(Comparator.comparingLong(data -> data.id));
		final JsonArray array = new JsonArray();
		sorted.forEach(data -> array.add(of(data)));
		return array;
	}

	public static JsonObject snapshot(Collection<? extends mtr.data.NameColorDataBase> stations, Collection<? extends mtr.data.NameColorDataBase> platforms, Collection<? extends mtr.data.NameColorDataBase> sidings, Collection<? extends mtr.data.NameColorDataBase> routes, Collection<? extends mtr.data.NameColorDataBase> depots) {
		final JsonObject result = new JsonObject();
		result.add("stations", of(stations));
		result.add("platforms", of(platforms));
		result.add("sidings", of(sidings));
		result.add("routes", of(routes));
		result.add("depots", of(depots));
		return result;
	}
}
