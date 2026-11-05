package transitsim.plan;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;

import java.util.List;

/**
 * A route's timetable with all its tables, links, return services and exceptions as bytes, for the component's own data
 * sets (S4e). The host's saves keep their own format.
 */
public final class TimetableBinary {

	private TimetableBinary() {
	}

	public static void write(BinaryWriter writer, RouteTimetable timetable) {
		writer.writeBoolean(timetable.enabled);
		writer.writeLong(timetable.entryPlatformId);
		final List<RouteTimetable> tables = timetable.getTables();
		writer.writeVarInt(tables.size());
		for (final RouteTimetable table : tables) {
			writer.writeInt(table.marginPercent);
			writer.writeInt(table.weekdays);
			writer.writeVarInt(table.services.size());
			table.services.forEach(writer::writeInt);
			writer.writeVarInt(table.links.size());
			table.links.forEach((service, link) -> {
				writer.writeInt(service);
				writer.writeLong(link.routeId);
				writer.writeInt(link.service);
				writer.writeInt(link.fromStop);
				writer.writeInt(link.toStop);
			});
			writer.writeVarInt(table.returnServices.size());
			table.returnServices.forEach(writer::writeInt);
			ServiceExceptions.write(writer, table);
		}
		// The service orders of each table come after all the tables, so data written before them still reads
		for (final RouteTimetable table : tables) {
			writeOrders(writer, table.returnOrders);
			writeOrders(writer, table.deployOrders);
		}
	}

	private static void writeOrders(BinaryWriter writer, java.util.Map<Integer, RouteTimetable.ServiceOrder> orders) {
		writer.writeVarInt(orders.size());
		new java.util.TreeMap<>(orders).forEach((service, order) -> {
			writer.writeInt(service);
			writer.writeInt(order.stop);
			writer.writeLong(order.depotId);
		});
	}

	private static void readOrders(BinaryReader reader, java.util.Map<Integer, RouteTimetable.ServiceOrder> orders) {
		for (int i = reader.readVarInt(); i > 0; i--) {
			final int service = reader.readInt();
			final int stop = reader.readInt();
			orders.put(service, new RouteTimetable.ServiceOrder(stop, reader.readLong()));
		}
	}

	/**
	 * Fills a new timetable with what {@link #write} wrote. Services only run as the continuation of another route's are
	 * marked by whoever has all the routes.
	 */
	public static RouteTimetable read(BinaryReader reader) {
		final RouteTimetable timetable = new RouteTimetable();
		timetable.enabled = reader.readBoolean();
		timetable.entryPlatformId = reader.readLong();
		final int tableCount = reader.readVarInt();
		final java.util.List<RouteTimetable> tables = new java.util.ArrayList<>();
		for (int i = 0; i < tableCount; i++) {
			final RouteTimetable table = i == 0 ? timetable : new RouteTimetable();
			table.marginPercent = reader.readInt();
			table.weekdays = reader.readInt();
			final int serviceCount = reader.readVarInt();
			for (int j = 0; j < serviceCount; j++) {
				table.services.add(reader.readInt());
			}
			final int linkCount = reader.readVarInt();
			for (int j = 0; j < linkCount; j++) {
				final int service = reader.readInt();
				final long routeId = reader.readLong();
				final int toService = reader.readInt();
				final int fromStop = reader.readInt();
				final int toStop = reader.readInt();
				table.links.put(service, new RouteTimetable.ServiceLink(routeId, toService, fromStop, toStop));
			}
			final int returnCount = reader.readVarInt();
			for (int j = 0; j < returnCount; j++) {
				table.returnServices.add(reader.readInt());
			}
			ServiceExceptions.read(reader, table);
			if (i > 0) {
				timetable.addTable(table);
			}
			tables.add(table);
		}
		if (reader.hasMore()) {
			for (final RouteTimetable table : tables) {
				readOrders(reader, table.returnOrders);
				readOrders(reader, table.deployOrders);
			}
		}
		timetable.sanitize();
		return timetable;
	}
}
