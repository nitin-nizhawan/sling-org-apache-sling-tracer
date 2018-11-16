/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.tracer.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.apache.sling.api.SlingHttpServletRequest;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import static org.apache.sling.tracer.internal.Util.trimToNull;

/**
 * Tracer provides support for enabling the logs for specific category at specific level and
 * only for specific request. It provides a very fine level of control via config provided
 * as part of HTTP request around how the logging should be performed for given category.
 *
 * This is specially useful for those parts of the system which are involved in every request.
 * For such parts enabling the log at global level would flood the logs and create lots of noise.
 * Using Tracer one can enable log for that request which is required to be probed
 */
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = Configuration.class)
public class LogTracer {
    /**
     * Request parameter name having comma separated value to determine list of tracers to
     * enable
     */
    public static final String PARAM_TRACER = "tracers";

    /**
     * Request param used to determine tracer config as part of request itself. Like
     * org.apache.sling;level=trace,org.apache.jackrabbit
     */
    public static final String PARAM_TRACER_CONFIG = "tracerConfig";

    public static final String HEADER_TRACER_CONFIG = "Sling-Tracer-Config";

    public static final String HEADER_TRACER = "Sling-Tracers";



    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(LogTracer.class);

    private final Map<String, TracerSet> tracers = new HashMap<String, TracerSet>();

    private BundleContext bundleContext;

    private ServiceRegistration slingFilterRegistration;

    private ServiceRegistration filterRegistration;

    private final AtomicReference<ServiceRegistration> logCollectorReg
            = new AtomicReference<ServiceRegistration>();

    private final AtomicInteger logCollectorRegCount = new AtomicInteger();

    private static final ThreadLocal<TracerContext> requestContextHolder = new ThreadLocal<TracerContext>();

    @Nullable
    private TracerLogServlet logServlet;

    private TraceLogRecorder recorder = TraceLogRecorder.DEFAULT;

    @Activate
    private void activate(Configuration config, BundleContext context) {
        this.bundleContext = context;
        initializeTracerSet(config);
        boolean enabled = config.enabled();
        if (enabled) {
            registerFilters(context);
            boolean servletEnabled = config.servletEnabled();

            if (servletEnabled) {
                int cacheSize = config.recordingCacheSizeInMB();
                long cacheDuration = config.recordingCacheDurationInSecs();
                boolean compressionEnabled = config.recordingCompressionEnabled();
                boolean gzipResponse = config.gzipResponse();

                this.logServlet = new TracerLogServlet(context, cacheSize, cacheDuration, compressionEnabled, gzipResponse);
                recorder = logServlet;
                LOG.info("Tracer recoding enabled with cacheSize {} MB, expiry {} secs, compression {}, gzip response {}",
                        cacheSize, cacheDuration, compressionEnabled, gzipResponse);
            }
            LOG.info("Log tracer enabled. Required filters registered. Tracer servlet enabled {}", servletEnabled);
        }
    }

    @Deactivate
    private void deactivate() {
        if (logServlet != null) {
            logServlet.unregister();
        }

        if (slingFilterRegistration != null) {
            slingFilterRegistration.unregister();
            slingFilterRegistration = null;
        }

        if (filterRegistration != null) {
            filterRegistration.unregister();
            filterRegistration = null;
        }

        ServiceRegistration reg = logCollectorReg.getAndSet(null);
        if (reg != null) {
            reg.unregister();
        }

        requestContextHolder.remove();
    }

    TracerContext getTracerContext(String tracerSetNames, String tracerConfig, Recording recording) {
        //No config or tracer set name provided. So tracing not required
        tracerConfig = trimToNull(tracerConfig);
        tracerSetNames = trimToNull(tracerSetNames);

        if (tracerSetNames == null && tracerConfig == null) {
            return null;
        }

        List<TracerConfig> configs = new ArrayList<TracerConfig>();

        List<String> invalidNames = new ArrayList<String>();
        if (tracerSetNames != null) {
            for (String tracerSetName : tracerSetNames.split(",")) {
                TracerSet ts = tracers.get(tracerSetName.toLowerCase(Locale.ENGLISH));
                if (ts != null) {
                    configs.addAll(ts.getConfigs());
                } else {
                    invalidNames.add(tracerSetName);
                }
            }
        }

        if (!invalidNames.isEmpty()) {
            LOG.warn("Invalid tracer set names passed [{}] as part of [{}]", invalidNames, tracerSetNames);
        }

        if (tracerConfig != null) {
            TracerSet ts = new TracerSet("custom", tracerConfig);
            configs.addAll(ts.getConfigs());
        }

        return new TracerContext(configs.toArray(new TracerConfig[configs.size()]), recording);
    }

    private void initializeTracerSet(Configuration config) {
        for (String tracerSetConfig : config.tracerSets()) {
            TracerSet tc = new TracerSet(tracerSetConfig);
            tracers.put(tc.getName(), tc);
        }
    }

    private void registerFilters(BundleContext context) {
        Dictionary<String, Object> slingFilterProps = new Hashtable<String, Object>();
        slingFilterProps.put("sling.filter.scope", "REQUEST");
        slingFilterProps.put(Constants.SERVICE_DESCRIPTION, "Sling Filter required for Log Tracer");
        slingFilterRegistration = context.registerService(Filter.class.getName(),
                new SlingTracerFilter(), slingFilterProps);

        Dictionary<String, Object> filterProps = new Hashtable<String, Object>();
        filterProps.put("pattern", "/.*");

        filterProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN, "/");
        filterProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT,
                "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=*)");

        filterProps.put(Constants.SERVICE_DESCRIPTION, "Servlet Filter required for Log Tracer");
        filterRegistration = context.registerService(Filter.class.getName(),
                new TracerFilter(), filterProps);
    }

    /**
     * TurboFilters causes slowness as they are executed on critical path
     * Hence care is taken to only register the filter only when required
     * Logic below ensures that filter is only registered for the duration
     * or request which needs to be "monitored".
     * <p/>
     * If multiple such request are performed then also only one filter gets
     * registered
     */
    private void registerLogCollector() {
        synchronized (logCollectorRegCount) {
            int count = logCollectorRegCount.getAndIncrement();
            if (count == 0) {
                ServiceRegistration reg = bundleContext.registerService(TurboFilter.class.getName(),
                        new LogCollector(), null);
                logCollectorReg.set(reg);
            }
        }
    }


    public <T> T withTracerConfig(String tracerSetNames,String tracerConfig,Callable<T> callable) throws Exception {
       Recording recording = Recording.NOOP;
          
       TracerContext requestTracerContext = requestContextHolder.get();
       if(requestTracerContext != null){
          recording = requestTracerContext.getRecording();
          
       }
       
       
       TracerContext tracerContext = getTracerContext(tracerSetNames,tracerConfig,recording);
       try{
          enableCollector(tracerContext);
          return callable.call();
       } finally {
            disableCollector();
       }
        
    }

    private void unregisterLogCollector() {
        synchronized (logCollectorRegCount) {
            int count = logCollectorRegCount.decrementAndGet();
            if (count == 0) {
                ServiceRegistration reg = logCollectorReg.getAndSet(null);
                reg.unregister();
            }
        }
    }
    
    void enableCollector(TracerContext tracerContext){
        requestContextHolder.set(tracerContext);
        registerLogCollector();
    }
    void disableCollector(){
         requestContextHolder.remove();
         unregisterLogCollector();
    }
    
    private abstract class AbstractFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
        }

        @Override
        public void destroy() {

        }

    }

    /**
     * Filter which registers at root and check for Tracer related params. If found to
     * be enabled then perform required setup for the logs to be captured.
     */
    private class TracerFilter extends AbstractFilter {

        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                             FilterChain filterChain) throws IOException, ServletException {

            //At generic filter level we just check for tracer hint via Header (later Cookie)
            //and not touch the request parameter to avoid eager initialization of request
            //parameter map

            HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;

            //Invoke at start so that header can be set. If done at end there is a chance
            //that response is committed
            Recording recording = recorder.startRecording(httpRequest, (HttpServletResponse) servletResponse);

            TracerContext tracerContext = getTracerContext(httpRequest.getHeader(HEADER_TRACER),
                    httpRequest.getHeader(HEADER_TRACER_CONFIG), recording);
            try {
                if (tracerContext != null) {
                    enableCollector(tracerContext);
                }
                filterChain.doFilter(servletRequest, servletResponse);
            } finally {
                if (tracerContext != null) {
                    disableCollector();
                }
                recorder.endRecording(httpRequest, recording);
            }
        }


    }

    /**
     * Sling level filter to extract the RequestProgressTracker and passes that to current
     * thread's TracerContent
     */
    private class SlingTracerFilter extends AbstractFilter {
        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                             FilterChain filterChain) throws IOException, ServletException {
            SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) servletRequest;
            TracerContext tracerContext = requestContextHolder.get();
            Recording recording = recorder.getRecordingForRequest(slingRequest);
            recording.registerTracker(slingRequest.getRequestProgressTracker());
            boolean createdContext = false;

            //Check if the global filter created context based on HTTP headers. If not
            //then check from request params
            if (tracerContext == null) {
                tracerContext = getTracerContext(slingRequest.getParameter(PARAM_TRACER),
                        slingRequest.getParameter(PARAM_TRACER_CONFIG), recording);
                if (tracerContext != null) {
                    createdContext = true;
                }
            }

            try {
                if (tracerContext != null) {
                    tracerContext.registerProgressTracker(slingRequest.getRequestProgressTracker());

                    //if context created in this filter then enable the collector
                    if (createdContext) {
                        enableCollector(tracerContext);
                    }
                }
                filterChain.doFilter(servletRequest, servletResponse);
            } finally {
                if (tracerContext != null) {
                    tracerContext.done();

                    if (createdContext) {
                        disableCollector();
                    }
                }
            }
        }
    }

    private static class LogCollector extends TurboFilter {
        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level,
                                  String format, Object[] params, Throwable t) {
            TracerContext tracer = requestContextHolder.get();
            if (tracer == null) {
                return FilterReply.NEUTRAL;
            }

            tracer.recordCategory(logger.getName());

            TracerConfig tc = tracer.findMatchingConfig(logger.getName(), level);
            if (tc != null) {
                if (format == null) {
                    return FilterReply.ACCEPT;
                }
                if (tracer.log(tc, level, logger.getName(), format, params)) {
                    return FilterReply.ACCEPT;
                }
            }

            return FilterReply.NEUTRAL;
        }
    }

}
