package mtr.servlet;

import mtr.SecurityConfig;
import mtr.data.RouteType;
import mtr.data.TransportMode;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public interface IServletHandler {

	long REQUEST_TIMEOUT_MILLIS = 30000;

	static void sendResponse(HttpServletResponse response, AsyncContext asyncContext, String content) {
		final ByteBuffer contentBytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
		try {
			if (!SecurityConfig.webserverCorsOrigin.isEmpty()) {
				response.addHeader("Access-Control-Allow-Origin", SecurityConfig.webserverCorsOrigin);
			}
			response.addHeader("Content-Type", "application/json");
			final ServletOutputStream servletOutputStream = response.getOutputStream();
			servletOutputStream.setWriteListener(new WriteListener() {
				@Override
				public void onWritePossible() throws IOException {
					while (servletOutputStream.isReady()) {
						if (!contentBytes.hasRemaining()) {
							response.setStatus(200);
							asyncContext.complete();
							return;
						}
						servletOutputStream.write(contentBytes.get());
					}
				}

				@Override
				public void onError(Throwable t) {
					asyncContext.complete();
				}
			});
		} catch (IOException | IllegalStateException e) {
			e.printStackTrace();
		}
	}

	static AsyncContext startAsync(HttpServletRequest request) {
		final AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(REQUEST_TIMEOUT_MILLIS);
		return asyncContext;
	}

	static String createRouteKey(TransportMode transportMode, RouteType routeType) {
		return (transportMode.toString() + "_" + routeType.toString()).toLowerCase(Locale.ENGLISH);
	}
}
