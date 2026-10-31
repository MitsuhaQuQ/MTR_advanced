package mtr.servlet;

import mtr.SecurityConfig;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSecurityFilter implements Filter {

	private final Map<String, long[]> requestCounts = new ConcurrentHashMap<>();

	private static final int MAX_REQUESTS_PER_MINUTE = 1200;
	private static final int MAX_ROUTE_REQUESTS_PER_MINUTE = 60;
	private static final int MAX_TRACKED_CLIENTS = 4096;
	private static final long MILLIS_PER_MINUTE = 60000;
	private static final String TOKEN_PARAMETER = "token";
	private static final String TOKEN_COOKIE = "mtr_token";
	private static final String ROUTE_PATH = "/route";
	private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob: https://minotar.net; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

	@Override
	public void init(FilterConfig filterConfig) {
	}

	@Override
	public void destroy() {
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
		final HttpServletRequest request = (HttpServletRequest) servletRequest;
		final HttpServletResponse response = (HttpServletResponse) servletResponse;

		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);

		final String method = request.getMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		} else if (!isAllowedHost(request.getServerName())) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
		} else if (!isAuthorized(request, response)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		} else if (!withinRateLimit(request)) {
			response.sendError(429);
		} else {
			filterChain.doFilter(servletRequest, servletResponse);
		}
	}

	// Checking the host blocks DNS rebinding, where a website points its own domain at the local web server
	private static boolean isAllowedHost(String host) {
		if (host == null) {
			return false;
		}

		final String lowerCaseHost = host.toLowerCase(Locale.ENGLISH);
		if (lowerCaseHost.equals("localhost") || lowerCaseHost.equals("127.0.0.1") || lowerCaseHost.equals("[::1]") || lowerCaseHost.equals("::1")) {
			return true;
		} else if (SecurityConfig.webserverAllowedHosts.isEmpty()) {
			return !SecurityConfig.isLoopbackBinding();
		} else {
			return SecurityConfig.webserverAllowedHosts.contains(lowerCaseHost);
		}
	}

	private static boolean isAuthorized(HttpServletRequest request, HttpServletResponse response) {
		final String token = SecurityConfig.webserverToken;
		if (token.isEmpty()) {
			return true;
		}

		final String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ") && matches(token, header.substring(7))) {
			return true;
		}

		final Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (final Cookie cookie : cookies) {
				if (TOKEN_COOKIE.equals(cookie.getName()) && matches(token, cookie.getValue())) {
					return true;
				}
			}
		}

		// Lets a browser open the map with ?token=... once, without the page needing to know about the token
		final String parameter = request.getParameter(TOKEN_PARAMETER);
		if (parameter != null && matches(token, parameter)) {
			response.setHeader("Set-Cookie", String.format("%s=%s; Path=/; HttpOnly; SameSite=Strict", TOKEN_COOKIE, token));
			return true;
		}

		return false;
	}

	private static boolean matches(String expected, String actual) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	private boolean withinRateLimit(HttpServletRequest request) {
		final String address = request.getRemoteAddr();
		final boolean isRoute = ROUTE_PATH.equals(request.getServletPath());
		if (requestCounts.size() > MAX_TRACKED_CLIENTS) {
			requestCounts.clear();
		}

		final long[] counter = requestCounts.computeIfAbsent(address, key -> new long[3]);
		synchronized (counter) {
			final long millis = System.currentTimeMillis();
			if (millis - counter[0] >= MILLIS_PER_MINUTE) {
				counter[0] = millis;
				counter[1] = 0;
				counter[2] = 0;
			}
			counter[1]++;
			if (isRoute) {
				counter[2]++;
				return counter[2] <= MAX_ROUTE_REQUESTS_PER_MINUTE;
			} else {
				return counter[1] <= MAX_REQUESTS_PER_MINUTE;
			}
		}
	}
}
