package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.packet.IPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrainSnapshotRecoveryTest implements IPacket {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void aParkedTrainIsResentInFullWithoutLeavingVisibilityOrMoving() {
		List<ResourceLocation> sent=new ArrayList<>();
		UpdateNearbyMovingObjects<TrainServer> sync=new UpdateNearbyMovingObjects<>(PACKET_DELETE_TRAINS,PACKET_UPDATE_TRAINS,PACKET_UPDATE_TRAIN_STATES,(player,id,packet)->{sent.add(id);packet.release();});
		TrainServer train=new TrainServer(1,2,60,"train","train_24_2",2,new ArrayList<>(),new ArrayList<>(),0,0,Train.ACCELERATION_DEFAULT,new ArrayList<>(),false,0,0);
		sync.startTick();sync.newDataSetInPlayerRange.put(null,Collections.singleton(train));sync.tick();
		assertEquals(Collections.singletonList(PACKET_UPDATE_TRAINS),sent);
		sent.clear();
		sync.startTick();sync.newDataSetInPlayerRange.put(null,Collections.singleton(train));sync.dataSetToSync.add(train);sync.tick();
		assertEquals(Collections.singletonList(PACKET_UPDATE_TRAIN_STATES),sent);
		sent.clear();
		assertTrue(sync.requestFullSnapshot(null,1000));
		assertFalse(sync.requestFullSnapshot(null,1001));
		sync.startTick();sync.newDataSetInPlayerRange.put(null,Collections.singleton(train));sync.tick();
		assertEquals(Arrays.asList(PACKET_DELETE_TRAINS,PACKET_UPDATE_TRAINS),sent);
	}
	@Test void recoveryDeletesStaleVehiclesUsingTheSameTickAsTheFullSnapshot() {
		List<byte[]> keepPackets=new ArrayList<>();
		UpdateNearbyMovingObjects<DataConverter> sync=new UpdateNearbyMovingObjects<>(PACKET_DELETE_TRAINS,PACKET_UPDATE_TRAINS,null,(player,id,packet)->{
			if(id.equals(PACKET_DELETE_TRAINS)) {byte[] bytes=new byte[packet.readableBytes()];packet.getBytes(packet.readerIndex(),bytes);keepPackets.add(bytes);}
			packet.release();
		});
		sync.startTick();sync.newDataSetInPlayerRange.put(null,Collections.singleton(new DataConverter(1,"old",0)));sync.tick();
		sync.requestFullSnapshot(null,1000);
		sync.startTick();sync.tick();
		assertEquals(1,keepPackets.size());
		FriendlyByteBuf packet=new FriendlyByteBuf(Unpooled.wrappedBuffer(keepPackets.get(0)));
		try {assertEquals(0,packet.readInt());} finally {packet.release();}
	}
}
