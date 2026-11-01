package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.Map;

/**
 * A rail node marked with a brush so that routes can pick it as a waypoint from the dashboard.
 */
public class KeyNode extends SerializedDataBase {

	public final BlockPos pos;

	private static final String KEY_POS = "pos";

	public KeyNode(BlockPos pos) {
		this.pos = pos;
	}

	public KeyNode(Map<String, Value> map) {
		pos = BlockPos.of(new MessagePackHelper(map).getLong(KEY_POS));
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		messagePacker.packString(KEY_POS).packLong(pos.asLong());
	}

	@Override
	public int messagePackLength() {
		return 1;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		packet.writeLong(pos.asLong());
	}
}
