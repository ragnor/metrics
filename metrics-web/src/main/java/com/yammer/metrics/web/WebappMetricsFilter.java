package com.yammer.metrics.web;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Filter} implementation which captures request information and a
 * breakdown of the response codes being returned.
 */
public abstract class WebappMetricsFilter implements Filter {
	private final static String TOTAL = "total";
	private final Map<Integer, String> meterNamesByStatusCode;
	private final String otherMetricName;
	private final ConcurrentMap<String, ConcurrentMap<Integer, Meter>> metersByPath = new ConcurrentHashMap<String, ConcurrentMap<Integer, Meter>>();
	private final ConcurrentMap<String, Timer> requestTimerByPath = new ConcurrentHashMap<String, Timer>();
	private final Counter activeRequests;

	/**
	 * Creates a new instance of the filter.
	 * 
	 * @param meterNamesByStatusCode
	 *            A map, keyed by status code, of meter names that we are
	 *            interested in.
	 * @param otherMetricName
	 *            The name used for the catch-all meter.
	 */
	public WebappMetricsFilter(Map<Integer, String> meterNamesByStatusCode,
			String otherMetricName) {
		this.meterNamesByStatusCode = meterNamesByStatusCode;
		this.otherMetricName = otherMetricName;

		metersByPath.put(TOTAL, createMap(TOTAL));

		this.activeRequests = Metrics.newCounter(WebappMetricsFilter.class,
				"activeRequests");
		requestTimerByPath.put("total", Metrics.newTimer(new MetricName(
				WebappMetricsFilter.class, TOTAL, "requests"),
				TimeUnit.MILLISECONDS, TimeUnit.SECONDS));

	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {

	}

	@Override
	public void destroy() {
		Metrics.shutdown();
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		final StatusExposingServletResponse wrappedResponse = new StatusExposingServletResponse(
				(HttpServletResponse) response);

		String servletPath = ((HttpServletRequest) request).getServletPath();
		String path = TOTAL;
		if (servletPath.length() != 1) {
			path = servletPath.split("/")[1];
		}

		activeRequests.inc();
		ComplexTimerComplex timer = new ComplexTimerComplex(path);
		timer.time();
		
		try {
			chain.doFilter(request, wrappedResponse);
		} finally {
			timer.stop();			
			activeRequests.dec();
			markMeterForStatusCode(path, wrappedResponse.getStatus());
	
		}
	}

	private void markMeterForStatusCode(String path, int status) {
		ConcurrentMap<Integer, Meter> map = metersByPath.get(path);
		if (map == null) {
			map = createMap(path);
		}

		increment(map, status);

		if (!TOTAL.equals(path)) {
			increment(metersByPath.get(TOTAL), status);
		}
	}
	

	private void increment(Map<Integer, Meter> map, int status) {
		final Meter metric = map.get(status);
		if (metric != null) {
			metric.mark();
		} else {
			map.get(-1).mark();
		}
	}

	private synchronized ConcurrentMap<Integer, Meter> createMap(String path) {
		if (metersByPath.get(path) != null) {
			return metersByPath.get(path);
		}
		
		ConcurrentMap<Integer, Meter> map = new ConcurrentHashMap<Integer, Meter>();

		for (Entry<Integer, String> entry : meterNamesByStatusCode.entrySet()) {
			map.put(entry.getKey(), Metrics.newMeter(new MetricName(
					WebappMetricsFilter.class, path, entry.getValue()),
					"responses", TimeUnit.SECONDS));
		}

		map.put(-1, Metrics.newMeter(new MetricName(WebappMetricsFilter.class,
				path, otherMetricName), "responses", TimeUnit.SECONDS));

		metersByPath.put(path, map);
		
		return map;
	}

	private static class StatusExposingServletResponse extends
			HttpServletResponseWrapper {
		private int httpStatus = 200;

		public StatusExposingServletResponse(HttpServletResponse response) {
			super(response);
		}

		@Override
		public void sendError(int sc) throws IOException {
			httpStatus = sc;
			super.sendError(sc);
		}

		@Override
		public void sendError(int sc, String msg) throws IOException {
			httpStatus = sc;
			super.sendError(sc, msg);
		}

		@Override
		public void setStatus(int sc) {
			httpStatus = sc;
			super.setStatus(sc);
		}

		public int getStatus() {
			return httpStatus;
		}
	}

	private class ComplexTimerComplex {

		private final String path;

		private TimerContext totalContext;

		private TimerContext pathContext;

		public ComplexTimerComplex(String path) {
			this.path = path;
		}

		public void time() {
			if (!TOTAL.equals(path)) {
				Timer pathTimer = requestTimerByPath.get(path);
				if (pathTimer == null) {
					pathTimer = Metrics.newTimer(new MetricName(
							WebappMetricsFilter.class, path, "requests"),
							TimeUnit.MILLISECONDS, TimeUnit.SECONDS);
					requestTimerByPath.put(path, pathTimer);
				}
				pathContext = pathTimer.time();
			}

			totalContext = requestTimerByPath.get(TOTAL).time();
		}

		public void stop() {
			if (pathContext != null) {
				pathContext.stop();
			}
			totalContext.stop();
		}

	}
}
