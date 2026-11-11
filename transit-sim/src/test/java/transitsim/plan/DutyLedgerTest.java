package transitsim.plan;

import org.junit.jupiter.api.Test;
import transitsim.io.*;
import transitsim.sim.RunRecord;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DutyLedgerTest {
	private DepotDuties.DayDuty day() {
		DepotDuties.DayDuty day = new DepotDuties.DayDuty(); day.weekdays = 127;
		for (int i = 0; i < 1000; i++) day.segments.add(new DepotDuties.Segment(1, i * 60000, 0, 1));
		return day;
	}
	@Test
	void liveQueriesUseASharedFrozenLedgerAndFindTheirSegment() {
		OperatingDay.set("UTC", 0);
		DepotDuties.DayDuty original = day();
		DutyLedger.Entry ledger = DutyLedger.intern(original);
		assertSame(ledger, DutyLedger.intern(original.copy()));
		original.segments.clear();
		assertEquals(1000, ledger.day().segments.size());
		assertEquals(10, ledger.segmentAt(0, 630000, 0));
		assertEquals(20, ledger.segmentAt(0, 630000, 20));
		assertEquals(999, ledger.segmentAt(0, 100000000, 0));
		assertSame(ledger, DutyLedger.read(ledger.key));
	}
	@Test
	void aPortableRecordStoresOneLedgerForManyTrainReferences() throws Exception {
		DutyLedger.Entry ledger = DutyLedger.intern(day());
		BinaryKeyed.Writer values = new BinaryKeyed.Writer(); values.writeString("duty_day", ledger.key);
		String path = "duty:skip:1/2/3/return";
		values.writeString("trip_path", PathKeyIndex.reference(path));
		ByteArrayBinary.Writer encoded = new ByteArrayBinary.Writer(); values.writeTo(encoded);
		byte[] service = encoded.toBytes(); assertTrue(service.length < 250);
		RunRecord record = new RunRecord(42);
		for (int i = 0; i < 50; i++) record.trains.add(new RunRecord.TrainRecord(1, i, 0, 0, 0, 0, 0, false, true, service, 0));
		byte[] bytes = record.toBytes();
		assertTrue(bytes.length < ledger.text.length() * 3);
		// Simulate loading in a process that has never seen these ledger references.
		java.lang.reflect.Field field = DutyLedger.class.getDeclaredField("entries"); field.setAccessible(true);
		Map<?, ?> entries = (Map<?, ?>) field.get(null); entries.clear();
		for (String name : new String[]{"byText", "byKey"}) {
			java.lang.reflect.Field cache = PathKeyIndex.class.getDeclaredField(name); cache.setAccessible(true); ((Map<?, ?>) cache.get(null)).clear();
		}
		RunRecord restored = RunRecord.fromBytes(bytes);
		assertEquals(path, PathKeyIndex.capture(restored.trains.get(0).service).text);
		assertEquals(50, restored.trains.size());
		assertEquals(1000, DutyLedger.capture(restored.trains.get(0).service).day().segments.size());
		assertArrayEquals(bytes, restored.toBytes());
	}
	@Test
	void legacyEmbeddedLedgersAndOldEmptyRecordsStillRead() {
		assertEquals(1000, DutyLedger.read(day().toText()).day().segments.size());
		ByteArrayBinary.Writer old = new ByteArrayBinary.Writer(); old.writeInt(5); old.writeLong(42);
		for (int i = 0; i < 7; i++) old.writeVarInt(0);
		assertTrue(RunRecord.fromBytes(old.toBytes()).trains.isEmpty());
	}
}
