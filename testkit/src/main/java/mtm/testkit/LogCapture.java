package mtm.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps the last warnings and errors of this game, numbered, so a test can ask for those logged since it started and fail
 * on a new exception.
 */
public final class LogCapture extends AbstractAppender {

	private static final int KEEP = 500;
	private static final Deque<JsonObject> EVENTS = new ArrayDeque<>();
	private static long sequence;

	private LogCapture() {
		super("MTM-TestKit", null, null, true, Property.EMPTY_ARRAY);
	}

	public static void install() {
		final LogCapture appender = new LogCapture();
		appender.start();
		((Logger) LogManager.getRootLogger()).addAppender(appender);
	}

	@Override
	public void append(LogEvent event) {
		if (!event.getLevel().isMoreSpecificThan(Level.WARN)) {
			return;
		}
		final JsonObject entry = new JsonObject();
		entry.addProperty("level", event.getLevel().name());
		entry.addProperty("logger", event.getLoggerName());
		entry.addProperty("thread", event.getThreadName());
		entry.addProperty("message", event.getMessage() == null ? "" : event.getMessage().getFormattedMessage());
		if (event.getThrown() != null) {
			entry.addProperty("thrown", event.getThrown().toString());
		}
		synchronized (EVENTS) {
			entry.addProperty("seq", ++sequence);
			EVENTS.addLast(entry);
			while (EVENTS.size() > KEEP) {
				EVENTS.removeFirst();
			}
		}
	}

	/**
	 * @return {"last": newest number, "events": those after {@code since}}
	 */
	public static JsonObject since(long since) {
		final JsonObject result = new JsonObject();
		final JsonArray events = new JsonArray();
		synchronized (EVENTS) {
			for (final JsonObject entry : EVENTS) {
				if (entry.get("seq").getAsLong() > since) {
					events.add(entry);
				}
			}
			result.addProperty("last", sequence);
		}
		result.add("events", events);
		return result;
	}
}
