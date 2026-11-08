package transitsim.plan;

import org.junit.jupiter.api.Test;
import transitsim.io.ByteArrayBinary;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same exceptions are written the same, however they were put in: what duties are planned from is keyed by these
 * bytes, and a key changing at each restart had duties planned again each time (optimisation round 4, step 4).
 */
class ServiceExceptionsOrderTest {

	private static RouteTimetable table(int capacity, int[] services) {
		final RouteTimetable table = new RouteTimetable();
		final Map<Integer, Map<Integer, ServiceExceptions.StopException>> exceptions = new HashMap<>(capacity);
		for (final int service : services) {
			final Map<Integer, ServiceExceptions.StopException> stops = new HashMap<>(capacity);
			for (final int stop : new int[]{9, 1, 5}) {
				final ServiceExceptions.StopException exception = new ServiceExceptions.StopException();
				exception.dwellSeconds = stop + 10;
				stops.put(stop, exception);
			}
			exceptions.put(service, stops);
		}
		table.exceptions.putAll(exceptions);
		return table;
	}

	private static byte[] bytes(RouteTimetable table) {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		ServiceExceptions.write(writer, table);
		return writer.toBytes();
	}

	@Test
	void theSameExceptionsAreWrittenTheSame() {
		final int[] services = {12600000, 29400000, 16800000, 25200000, 0, 37800000, 81000000};
		final int[] reversed = new int[services.length];
		for (int i = 0; i < services.length; i++) {
			reversed[i] = services[services.length - 1 - i];
		}
		assertArrayEquals(bytes(table(4, services)), bytes(table(256, reversed)));
		// By service, then by stop
		final int[] values = ServiceExceptions.toInts(table(4, services));
		for (int i = ServiceExceptions.SAVED_INTS; i < values.length; i += ServiceExceptions.SAVED_INTS) {
			final int previousService = values[i - ServiceExceptions.SAVED_INTS], service = values[i];
			assertTrue(previousService < service || previousService == service && values[i - ServiceExceptions.SAVED_INTS + 1] < values[i + 1], "in order at " + i);
		}
	}
}
