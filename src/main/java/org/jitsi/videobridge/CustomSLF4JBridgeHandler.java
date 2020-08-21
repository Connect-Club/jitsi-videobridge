package org.jitsi.videobridge;

import net.logstash.logback.marker.Markers;
import org.apache.commons.lang3.StringUtils;
import org.jitsi.utils.logging2.ContextLogRecord;
import org.jitsi.utils.logging2.LogContext;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.slf4j.spi.LocationAwareLogger;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

public class CustomSLF4JBridgeHandler extends SLF4JBridgeHandler {

    private static final String FQCN = java.util.logging.Logger.class.getName();

    private static final int TRACE_LEVEL_THRESHOLD = Level.FINER.intValue();
    private static final int DEBUG_LEVEL_THRESHOLD = Level.FINE.intValue();
    private static final int INFO_LEVEL_THRESHOLD = Level.INFO.intValue();
    private static final int WARN_LEVEL_THRESHOLD = Level.WARNING.intValue();

    private static Marker getMarker(ContextLogRecord contextLogRecord) {
        String context = contextLogRecord.getContext();
        if (StringUtils.isBlank(context) || !context.startsWith(LogContext.CONTEXT_START_TOKEN) || !context.endsWith(LogContext.CONTEXT_END_TOKEN)) {
            return null;
        }
        String[] parts = context.substring(LogContext.CONTEXT_START_TOKEN.length(), context.length() - LogContext.CONTEXT_END_TOKEN.length()).split(" ");
        Map<String, String> mappedContext = Arrays.stream(parts)
                .map(x -> x.split("="))
                .collect(Collectors.toMap(x -> x[0], x -> x[1]));
        return Markers.appendEntries(mappedContext);
    }

    private String getMessageI18N(LogRecord record) {
        String message = record.getMessage();

        if (message == null) {
            return null;
        }

        ResourceBundle bundle = record.getResourceBundle();
        if (bundle != null) {
            try {
                message = bundle.getString(message);
            } catch (MissingResourceException e) {
            }
        }
        Object[] params = record.getParameters();
        // avoid formatting when there are no or 0 parameters. see also
        // http://jira.qos.ch/browse/SLF4J-203
        if (params != null && params.length > 0) {
            try {
                message = MessageFormat.format(message, params);
            } catch (IllegalArgumentException e) {
                // default to the same behavior as in java.util.logging.Formatter.formatMessage(LogRecord)
                // see also http://jira.qos.ch/browse/SLF4J-337
                return message;
            }
        }
        return message;
    }

    @Override
    protected void callLocationAwareLogger(LocationAwareLogger lal, LogRecord record) {
        int julLevelValue = record.getLevel().intValue();
        int slf4jLevel;

        if (julLevelValue <= TRACE_LEVEL_THRESHOLD) {
            slf4jLevel = LocationAwareLogger.TRACE_INT;
        } else if (julLevelValue <= DEBUG_LEVEL_THRESHOLD) {
            slf4jLevel = LocationAwareLogger.DEBUG_INT;
        } else if (julLevelValue <= INFO_LEVEL_THRESHOLD) {
            slf4jLevel = LocationAwareLogger.INFO_INT;
        } else if (julLevelValue <= WARN_LEVEL_THRESHOLD) {
            slf4jLevel = LocationAwareLogger.WARN_INT;
        } else {
            slf4jLevel = LocationAwareLogger.ERROR_INT;
        }
        String i18nMessage = getMessageI18N(record);
        Marker marker = null;
        if (record instanceof ContextLogRecord) {
            marker = getMarker((ContextLogRecord) record);
        }
        lal.log(marker, FQCN, slf4jLevel, i18nMessage, null, record.getThrown());
    }

    @Override
    protected void callPlainSLF4JLogger(Logger slf4jLogger, LogRecord record) {
        String i18nMessage = getMessageI18N(record);
        Marker marker = null;
        if (record instanceof ContextLogRecord) {
            marker = getMarker((ContextLogRecord) record);
        }
        int julLevelValue = record.getLevel().intValue();
        if (julLevelValue <= TRACE_LEVEL_THRESHOLD) {
            slf4jLogger.trace(marker, i18nMessage, record.getThrown());
        } else if (julLevelValue <= DEBUG_LEVEL_THRESHOLD) {
            slf4jLogger.debug(marker, i18nMessage, record.getThrown());
        } else if (julLevelValue <= INFO_LEVEL_THRESHOLD) {
            slf4jLogger.info(marker, i18nMessage, record.getThrown());
        } else if (julLevelValue <= WARN_LEVEL_THRESHOLD) {
            slf4jLogger.warn(marker, i18nMessage, record.getThrown());
        } else {
            slf4jLogger.error(marker, i18nMessage, record.getThrown());
        }
    }
}
