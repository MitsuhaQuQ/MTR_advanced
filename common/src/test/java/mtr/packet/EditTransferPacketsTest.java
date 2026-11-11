package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.data.EditTransferAssembler;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EditTransferPacketsTest {
	@Test void largeTimetableIsReassembledOnlyAfterEverySmallPacketArrives() {
		byte[] bytes = new byte[750000];
		for (int i=0;i<bytes.length;i++) bytes[i]=(byte)(i*31);
		FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		EditTransferAssembler assembler = new EditTransferAssembler();
		AtomicInteger complete = new AtomicInteger(), chunks = new AtomicInteger();
		try {
			EditTransferPackets.send(IPacket.PACKET_EDIT_BATCH,packet,(id,part)-> {
				assertEquals(IPacket.PACKET_EDIT_FRAGMENT,id); assertTrue(part.readableBytes()<32767);
				long transfer=part.readLong(); assertTrue(part.readBoolean());
				int total=part.readVarInt(), offset=part.readVarInt();
				byte[] result=assembler.accept(transfer,1,total,offset,part.readByteArray(EditTransferAssembler.CHUNK_BYTES),100);
				if (result!=null) { assertArrayEquals(bytes,result); complete.incrementAndGet(); }
				else assertEquals(0,complete.get());
				chunks.incrementAndGet(); part.release();
			});
			assertEquals(1,complete.get()); assertTrue(chunks.get()>1);
		} finally { packet.release(); }
	}
	@Test void rejectsGapsChangedHeadersOversizeAndExpiredTransfers() {
		EditTransferAssembler a = new EditTransferAssembler();
		assertNull(a.accept(1,0,10,0,new byte[5],0));
		assertThrows(IllegalArgumentException.class,()->a.accept(1,0,10,6,new byte[4],1));
		assertNull(a.accept(2,0,10,0,new byte[5],0));
		assertThrows(IllegalArgumentException.class,()->a.accept(2,1,10,5,new byte[5],1));
		assertThrows(IllegalArgumentException.class,()->a.accept(3,0,EditTransferAssembler.MAX_BYTES+1,0,new byte[1],0));
		assertNull(a.accept(4,0,10,0,new byte[5],0));
		assertThrows(IllegalArgumentException.class,()->a.accept(4,0,10,5,new byte[5],30001));
		assertArrayEquals(new byte[5],a.accept(5,0,5,0,new byte[5],30002));
	}
	@Test void smallEditsKeepTheirOriginalChannelAndOversizeFailsBeforeSending() {
		FriendlyByteBuf small = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[100]));
		try { EditTransferPackets.send(IPacket.PACKET_EDIT,small,(id,data)-> { assertEquals(IPacket.PACKET_EDIT,id); assertSame(small,data); }); }
		finally { small.release(); }
		FriendlyByteBuf large = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[EditTransferAssembler.MAX_BYTES+1]));
		try { assertThrows(IllegalArgumentException.class,()->EditTransferPackets.send(IPacket.PACKET_EDIT_BATCH,large,(id,data)->fail("must not send"))); }
		finally { large.release(); }
	}
}
