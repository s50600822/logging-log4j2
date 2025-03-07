/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.layout;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LoggingException;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.TlsSyslogFrame;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.plugins.Node;
import org.apache.logging.log4j.plugins.Plugin;
import org.apache.logging.log4j.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.plugins.PluginElement;
import org.apache.logging.log4j.plugins.PluginFactory;
import org.apache.logging.log4j.core.net.Facility;
import org.apache.logging.log4j.core.net.Priority;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.core.pattern.PatternFormatter;
import org.apache.logging.log4j.core.pattern.PatternParser;
import org.apache.logging.log4j.core.pattern.ThrowablePatternConverter;
import org.apache.logging.log4j.core.util.NetUtils;
import org.apache.logging.log4j.core.util.Patterns;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageCollectionMessage;
import org.apache.logging.log4j.message.StructuredDataCollectionMessage;
import org.apache.logging.log4j.message.StructuredDataId;
import org.apache.logging.log4j.message.StructuredDataMessage;
import org.apache.logging.log4j.core.util.ProcessIdUtil;
import org.apache.logging.log4j.util.StringBuilders;
import org.apache.logging.log4j.util.Strings;

/**
 * Formats a log event in accordance with RFC 5424.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5424">RFC 5424</a>
 */
@Plugin(name = "Rfc5424Layout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public final class Rfc5424Layout extends AbstractStringLayout {

    /**
     * Not a very good default - it is the Apache Software Foundation's enterprise number.
     */
    public static final int DEFAULT_ENTERPRISE_NUMBER = 18060;
    /**
     * The default event id.
     */
    public static final String DEFAULT_ID = "Audit";
    /**
     * Match newlines in a platform-independent manner.
     */
    public static final Pattern NEWLINE_PATTERN = Pattern.compile("\\r?\\n");
    /**
     * Match characters which require escaping.
     */
    public static final Pattern PARAM_VALUE_ESCAPE_PATTERN = Pattern.compile("[\\\"\\]\\\\]");

    /**
     * Default MDC ID: {@value} .
     */
    public static final String DEFAULT_MDCID = "mdc";

    private static final String LF = "\n";
    private static final int TWO_DIGITS = 10;
    private static final int THREE_DIGITS = 100;
    private static final int MILLIS_PER_MINUTE = 60000;
    private static final int MINUTES_PER_HOUR = 60;
    private static final String COMPONENT_KEY = "RFC5424-Converter";

    private final Facility facility;
    private final String defaultId;
    private final int enterpriseNumber;
    private final boolean includeMdc;
    private final String mdcId;
    private final StructuredDataId mdcSdId;
    private final String localHostName;
    private final String appName;
    private final String messageId;
    private final String configName;
    private final String mdcPrefix;
    private final String eventPrefix;
    private final List<String> mdcExcludes;
    private final List<String> mdcIncludes;
    private final List<String> mdcRequired;
    private final ListChecker listChecker;
    private final ListChecker noopChecker = new NoopChecker();
    private final boolean includeNewLine;
    private final String escapeNewLine;
    private final boolean useTlsMessageFormat;

    private long lastTimestamp = -1;
    private String timestamppStr;

    private final List<PatternFormatter> exceptionFormatters;
    private final Map<String, FieldFormatter> fieldFormatters;
    private final String procId;

    private Rfc5424Layout(final Configuration config, final Facility facility, final String id, final int ein,
            final boolean includeMDC, final boolean includeNL, final String escapeNL, final String mdcId,
            final String mdcPrefix, final String eventPrefix, final String appName, final String messageId,
            final String excludes, final String includes, final String required, final Charset charset,
            final String exceptionPattern, final boolean useTLSMessageFormat, final LoggerFields[] loggerFields) {
        super(charset);
        final PatternParser exceptionParser = createPatternParser(config, ThrowablePatternConverter.class);
        exceptionFormatters = exceptionPattern == null ? null : exceptionParser.parse(exceptionPattern);
        this.facility = facility;
        this.defaultId = id == null ? DEFAULT_ID : id;
        this.enterpriseNumber = ein;
        this.includeMdc = includeMDC;
        this.includeNewLine = includeNL;
        this.escapeNewLine = escapeNL == null ? null : Matcher.quoteReplacement(escapeNL);
        this.mdcId = mdcId != null ? mdcId : id == null ? DEFAULT_MDCID : id;
        this.mdcSdId = new StructuredDataId(this.mdcId, enterpriseNumber, null, null);
        this.mdcPrefix = mdcPrefix;
        this.eventPrefix = eventPrefix;
        this.appName = appName;
        this.messageId = messageId;
        this.useTlsMessageFormat = useTLSMessageFormat;
        this.localHostName = NetUtils.getLocalHostname();
        ListChecker c = null;
        if (excludes != null) {
            final String[] array = excludes.split(Patterns.COMMA_SEPARATOR);
            if (array.length > 0) {
                c = new ExcludeChecker();
                mdcExcludes = new ArrayList<>(array.length);
                for (final String str : array) {
                    mdcExcludes.add(str.trim());
                }
            } else {
                mdcExcludes = null;
            }
        } else {
            mdcExcludes = null;
        }
        if (includes != null) {
            final String[] array = includes.split(Patterns.COMMA_SEPARATOR);
            if (array.length > 0) {
                c = new IncludeChecker();
                mdcIncludes = new ArrayList<>(array.length);
                for (final String str : array) {
                    mdcIncludes.add(str.trim());
                }
            } else {
                mdcIncludes = null;
            }
        } else {
            mdcIncludes = null;
        }
        if (required != null) {
            final String[] array = required.split(Patterns.COMMA_SEPARATOR);
            if (array.length > 0) {
                mdcRequired = new ArrayList<>(array.length);
                for (final String str : array) {
                    mdcRequired.add(str.trim());
                }
            } else {
                mdcRequired = null;
            }

        } else {
            mdcRequired = null;
        }
        this.listChecker = c != null ? c : noopChecker;
        final String name = config == null ? null : config.getName();
        configName = Strings.isNotEmpty(name) ? name : null;
        this.fieldFormatters = createFieldFormatters(loggerFields, config);
        this.procId = ProcessIdUtil.getProcessId();
    }

    private Map<String, FieldFormatter> createFieldFormatters(final LoggerFields[] loggerFields,
            final Configuration config) {
        final Map<String, FieldFormatter> sdIdMap = new HashMap<>(loggerFields == null ? 0 : loggerFields.length);
        if (loggerFields != null) {
            for (final LoggerFields loggerField : loggerFields) {
                final StructuredDataId key = loggerField.getSdId() == null ? mdcSdId : loggerField.getSdId();
                final Map<String, List<PatternFormatter>> sdParams = new HashMap<>();
                final Map<String, String> fields = loggerField.getMap();
                if (!fields.isEmpty()) {
                    final PatternParser fieldParser = createPatternParser(config, null);

                    for (final Map.Entry<String, String> entry : fields.entrySet()) {
                        final List<PatternFormatter> formatters = fieldParser.parse(entry.getValue());
                        sdParams.put(entry.getKey(), formatters);
                    }
                    final FieldFormatter fieldFormatter = new FieldFormatter(sdParams,
                            loggerField.getDiscardIfAllFieldsAreEmpty());
                    sdIdMap.put(key.toString(), fieldFormatter);
                }
            }
        }
        return sdIdMap.size() > 0 ? sdIdMap : null;
    }

    /**
     * Create a PatternParser.
     *
     * @param config The Configuration.
     * @param filterClass Filter the returned plugins after calling the plugin manager.
     * @return The PatternParser.
     */
    private static PatternParser createPatternParser(final Configuration config,
            final Class<? extends PatternConverter> filterClass) {
        if (config == null) {
            return new PatternParser(config, PatternLayout.KEY, LogEventPatternConverter.class, filterClass);
        }
        PatternParser parser = config.getComponent(COMPONENT_KEY);
        if (parser == null) {
            parser = new PatternParser(config, PatternLayout.KEY, ThrowablePatternConverter.class);
            config.addComponent(COMPONENT_KEY, parser);
            parser = config.getComponent(COMPONENT_KEY);
        }
        return parser;
    }

    /**
     * Gets this Rfc5424Layout's content format. Specified by:
     * <ul>
     * <li>Key: "structured" Value: "true"</li>
     * <li>Key: "format" Value: "RFC5424"</li>
     * </ul>
     *
     * @return Map of content format keys supporting Rfc5424Layout
     */
    @Override
    public Map<String, String> getContentFormat() {
        final Map<String, String> result = new HashMap<>();
        result.put("structured", "true");
        result.put("formatType", "RFC5424");
        return result;
    }

    /**
     * Formats a {@link org.apache.logging.log4j.core.LogEvent} in conformance with the RFC 5424 Syslog specification.
     *
     * @param event The LogEvent.
     * @return The RFC 5424 String representation of the LogEvent.
     */
    @Override
    public String toSerializable(final LogEvent event) {
        final StringBuilder buf = getStringBuilder();
        appendPriority(buf, event.getLevel());
        appendTimestamp(buf, event.getTimeMillis());
        appendSpace(buf);
        appendHostName(buf);
        appendSpace(buf);
        appendAppName(buf);
        appendSpace(buf);
        appendProcessId(buf);
        appendSpace(buf);
        appendMessageId(buf, event.getMessage());
        appendSpace(buf);
        appendStructuredElements(buf, event);
        appendMessage(buf, event);
        if (useTlsMessageFormat) {
            return new TlsSyslogFrame(buf.toString()).toString();
        }
        return buf.toString();
    }

    private void appendPriority(final StringBuilder buffer, final Level logLevel) {
        buffer.append('<');
        buffer.append(Priority.getPriority(facility, logLevel));
        buffer.append(">1 ");
    }

    private void appendTimestamp(final StringBuilder buffer, final long milliseconds) {
        buffer.append(computeTimeStampString(milliseconds));
    }

    private void appendSpace(final StringBuilder buffer) {
        buffer.append(' ');
    }

    private void appendHostName(final StringBuilder buffer) {
        buffer.append(localHostName);
    }

    private void appendAppName(final StringBuilder buffer) {
        if (appName != null) {
            buffer.append(appName);
        } else if (configName != null) {
            buffer.append(configName);
        } else {
            buffer.append('-');
        }
    }

    private void appendProcessId(final StringBuilder buffer) {
        buffer.append(getProcId());
    }

    private void appendMessageId(final StringBuilder buffer, final Message message) {
        final boolean isStructured = message instanceof StructuredDataMessage;
        final String type = isStructured ? ((StructuredDataMessage) message).getType() : null;
        if (type != null) {
            buffer.append(type);
        } else if (messageId != null) {
            buffer.append(messageId);
        } else {
            buffer.append('-');
        }
    }

    private void appendMessage(final StringBuilder buffer, final LogEvent event) {
        final Message message = event.getMessage();
        // This layout formats StructuredDataMessages instead of delegating to the Message itself.
        final String text = (message instanceof StructuredDataMessage || message instanceof MessageCollectionMessage)
                ? message.getFormat() : message.getFormattedMessage();

        if (text != null && text.length() > 0) {
            buffer.append(' ').append(escapeNewlines(text, escapeNewLine));
        }

        if (exceptionFormatters != null && event.getThrown() != null) {
            final StringBuilder exception = new StringBuilder(LF);
            for (final PatternFormatter formatter : exceptionFormatters) {
                formatter.format(event, exception);
            }
            buffer.append(escapeNewlines(exception.toString(), escapeNewLine));
        }
        if (includeNewLine) {
            buffer.append(LF);
        }
    }

    private void appendStructuredElements(final StringBuilder buffer, final LogEvent event) {
        final Message message = event.getMessage();
        final boolean isStructured = message instanceof StructuredDataMessage ||
                message instanceof StructuredDataCollectionMessage;

        if (!isStructured && (fieldFormatters != null && fieldFormatters.isEmpty()) && !includeMdc) {
            buffer.append('-');
            return;
        }

        final Map<String, StructuredDataElement> sdElements = new HashMap<>();
        final Map<String, String> contextMap = event.getContextData().toMap();

        if (mdcRequired != null) {
            checkRequired(contextMap);
        }

        if (fieldFormatters != null) {
            for (final Map.Entry<String, FieldFormatter> sdElement : fieldFormatters.entrySet()) {
                final String sdId = sdElement.getKey();
                final StructuredDataElement elem = sdElement.getValue().format(event);
                sdElements.put(sdId, elem);
            }
        }

        if (includeMdc && contextMap.size() > 0) {
            final String mdcSdIdStr = mdcSdId.toString();
            final StructuredDataElement union = sdElements.get(mdcSdIdStr);
            if (union != null) {
                union.union(contextMap);
                sdElements.put(mdcSdIdStr, union);
            } else {
                final StructuredDataElement formattedContextMap = new StructuredDataElement(contextMap, mdcPrefix, false);
                sdElements.put(mdcSdIdStr, formattedContextMap);
            }
        }

        if (isStructured) {
            if (message instanceof MessageCollectionMessage) {
                for (StructuredDataMessage data : ((StructuredDataCollectionMessage)message)) {
                    addStructuredData(sdElements, data);
                }
            } else {
                addStructuredData(sdElements, (StructuredDataMessage) message);
            }
        }

        if (sdElements.isEmpty()) {
            buffer.append('-');
            return;
        }

        for (final Map.Entry<String, StructuredDataElement> entry : sdElements.entrySet()) {
            formatStructuredElement(entry.getKey(), entry.getValue(), buffer, listChecker);
        }
    }

    private void addStructuredData(final Map<String, StructuredDataElement> sdElements, final StructuredDataMessage data) {
        final Map<String, String> map = data.getData();
        final StructuredDataId id = data.getId();
        final String sdId = getId(id);

        if (sdElements.containsKey(sdId)) {
            final StructuredDataElement union = sdElements.get(id.toString());
            union.union(map);
            sdElements.put(sdId, union);
        } else {
            final StructuredDataElement formattedData = new StructuredDataElement(map, eventPrefix, false);
            sdElements.put(sdId, formattedData);
        }
    }

    private String escapeNewlines(final String text, final String replacement) {
        if (null == replacement) {
            return text;
        }
        return NEWLINE_PATTERN.matcher(text).replaceAll(replacement);
    }

    protected String getProcId() {
        return procId;
    }

    protected List<String> getMdcExcludes() {
        return mdcExcludes;
    }

    protected List<String> getMdcIncludes() {
        return mdcIncludes;
    }

    private String computeTimeStampString(final long now) {
        long last;
        synchronized (this) {
            last = lastTimestamp;
            if (now == lastTimestamp) {
                return timestamppStr;
            }
        }

        final StringBuilder buffer = new StringBuilder();
        final Calendar cal = new GregorianCalendar();
        cal.setTimeInMillis(now);
        buffer.append(Integer.toString(cal.get(Calendar.YEAR)));
        buffer.append('-');
        pad(cal.get(Calendar.MONTH) + 1, TWO_DIGITS, buffer);
        buffer.append('-');
        pad(cal.get(Calendar.DAY_OF_MONTH), TWO_DIGITS, buffer);
        buffer.append('T');
        pad(cal.get(Calendar.HOUR_OF_DAY), TWO_DIGITS, buffer);
        buffer.append(':');
        pad(cal.get(Calendar.MINUTE), TWO_DIGITS, buffer);
        buffer.append(':');
        pad(cal.get(Calendar.SECOND), TWO_DIGITS, buffer);
        buffer.append('.');
        pad(cal.get(Calendar.MILLISECOND), THREE_DIGITS, buffer);

        int tzmin = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / MILLIS_PER_MINUTE;
        if (tzmin == 0) {
            buffer.append('Z');
        } else {
            if (tzmin < 0) {
                tzmin = -tzmin;
                buffer.append('-');
            } else {
                buffer.append('+');
            }
            final int tzhour = tzmin / MINUTES_PER_HOUR;
            tzmin -= tzhour * MINUTES_PER_HOUR;
            pad(tzhour, TWO_DIGITS, buffer);
            buffer.append(':');
            pad(tzmin, TWO_DIGITS, buffer);
        }
        synchronized (this) {
            if (last == lastTimestamp) {
                lastTimestamp = now;
                timestamppStr = buffer.toString();
            }
        }
        return buffer.toString();
    }

    private void pad(final int val, int max, final StringBuilder buf) {
        while (max > 1) {
            if (val < max) {
                buf.append('0');
            }
            max = max / TWO_DIGITS;
        }
        buf.append(Integer.toString(val));
    }

    private void formatStructuredElement(final String id, final StructuredDataElement data,
            final StringBuilder sb, final ListChecker checker) {
        if ((id == null && defaultId == null) || data.discard()) {
            return;
        }

        sb.append('[');
        sb.append(id);
        if (!mdcSdId.toString().equals(id)) {
            appendMap(data.getPrefix(), data.getFields(), sb, noopChecker);
        } else {
            appendMap(data.getPrefix(), data.getFields(), sb, checker);
        }
        sb.append(']');
    }

    private String getId(final StructuredDataId id) {
        final StringBuilder sb = new StringBuilder();
        if (id == null || id.getName() == null) {
            sb.append(defaultId);
        } else {
            sb.append(id.getName());
        }
        int ein = id != null ? id.getEnterpriseNumber() : enterpriseNumber;
        if (ein < 0) {
            ein = enterpriseNumber;
        }
        if (ein >= 0) {
            sb.append('@').append(ein);
        }
        return sb.toString();
    }

    private void checkRequired(final Map<String, String> map) {
        for (final String key : mdcRequired) {
            final String value = map.get(key);
            if (value == null) {
                throw new LoggingException("Required key " + key + " is missing from the " + mdcId);
            }
        }
    }

    private void appendMap(final String prefix, final Map<String, String> map, final StringBuilder sb,
            final ListChecker checker) {
        final SortedMap<String, String> sorted = new TreeMap<>(map);
        for (final Map.Entry<String, String> entry : sorted.entrySet()) {
            if (checker.check(entry.getKey()) && entry.getValue() != null) {
                sb.append(' ');
                if (prefix != null) {
                    sb.append(prefix);
                }
                final String safeKey = escapeNewlines(escapeSDParams(entry.getKey()), escapeNewLine);
                final String safeValue = escapeNewlines(escapeSDParams(entry.getValue()), escapeNewLine);
                StringBuilders.appendKeyDqValue(sb, safeKey, safeValue);
            }
        }
    }

    private String escapeSDParams(final String value) {
        return PARAM_VALUE_ESCAPE_PATTERN.matcher(value).replaceAll("\\\\$0");
    }

    /**
     * Interface used to check keys in a Map.
     */
    private interface ListChecker {
        boolean check(String key);
    }

    /**
     * Includes only the listed keys.
     */
    private class IncludeChecker implements ListChecker {
        @Override
        public boolean check(final String key) {
            return mdcIncludes.contains(key);
        }
    }

    /**
     * Excludes the listed keys.
     */
    private class ExcludeChecker implements ListChecker {
        @Override
        public boolean check(final String key) {
            return !mdcExcludes.contains(key);
        }
    }

    /**
     * Does nothing.
     */
    private class NoopChecker implements ListChecker {
        @Override
        public boolean check(final String key) {
            return true;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("facility=").append(facility.name());
        sb.append(" appName=").append(appName);
        sb.append(" defaultId=").append(defaultId);
        sb.append(" enterpriseNumber=").append(enterpriseNumber);
        sb.append(" newLine=").append(includeNewLine);
        sb.append(" includeMDC=").append(includeMdc);
        sb.append(" messageId=").append(messageId);
        return sb.toString();
    }

    /**
     * Create the RFC 5424 Layout.
     *
     * @param facility The Facility is used to try to classify the message.
     * @param id The default structured data id to use when formatting according to RFC 5424.
     * @param enterpriseNumber The IANA enterprise number.
     * @param includeMDC Indicates whether data from the ThreadContextMap will be included in the RFC 5424 Syslog
     *            record. Defaults to "true:.
     * @param mdcId The id to use for the MDC Structured Data Element.
     * @param mdcPrefix The prefix to add to MDC key names.
     * @param eventPrefix The prefix to add to event key names.
     * @param newLine If true, a newline will be appended to the end of the syslog record. The default is false.
     * @param escapeNL String that should be used to replace newlines within the message text.
     * @param appName The value to use as the APP-NAME in the RFC 5424 syslog record.
     * @param msgId The default value to be used in the MSGID field of RFC 5424 syslog records.
     * @param excludes A comma separated list of MDC keys that should be excluded from the LogEvent.
     * @param includes A comma separated list of MDC keys that should be included in the FlumeEvent.
     * @param required A comma separated list of MDC keys that must be present in the MDC.
     * @param exceptionPattern The pattern for formatting exceptions.
     * @param useTlsMessageFormat If true the message will be formatted according to RFC 5425.
     * @param loggerFields Container for the KeyValuePairs containing the patterns
     * @param config The Configuration. Some Converters require access to the Interpolator.
     * @return An Rfc5424Layout.
     */
    @PluginFactory
    public static Rfc5424Layout createLayout(
            // @formatter:off
            @PluginAttribute(value = "facility", defaultString = "LOCAL0") final Facility facility,
            @PluginAttribute("id") final String id,
            @PluginAttribute(value = "enterpriseNumber", defaultInt = DEFAULT_ENTERPRISE_NUMBER)
            final int enterpriseNumber,
            @PluginAttribute(value = "includeMDC", defaultBoolean = true) final boolean includeMDC,
            @PluginAttribute(value = "mdcId", defaultString = DEFAULT_MDCID) final String mdcId,
            @PluginAttribute("mdcPrefix") final String mdcPrefix,
            @PluginAttribute("eventPrefix") final String eventPrefix,
            @PluginAttribute(value = "newLine") final boolean newLine,
            @PluginAttribute("newLineEscape") final String escapeNL,
            @PluginAttribute("appName") final String appName,
            @PluginAttribute("messageId") final String msgId,
            @PluginAttribute("mdcExcludes") final String excludes,
            @PluginAttribute("mdcIncludes") String includes,
            @PluginAttribute("mdcRequired") final String required,
            @PluginAttribute("exceptionPattern") final String exceptionPattern,
            // RFC 5425
            @PluginAttribute(value = "useTlsMessageFormat") final boolean useTlsMessageFormat,
            @PluginElement("LoggerFields") final LoggerFields[] loggerFields,
            @PluginConfiguration final Configuration config) {
        // @formatter:on
        if (includes != null && excludes != null) {
            LOGGER.error("mdcIncludes and mdcExcludes are mutually exclusive. Includes wil be ignored");
            includes = null;
        }

        return new Rfc5424Layout(config, facility, id, enterpriseNumber, includeMDC, newLine, escapeNL, mdcId,
                mdcPrefix, eventPrefix, appName, msgId, excludes, includes, required, StandardCharsets.UTF_8,
                exceptionPattern, useTlsMessageFormat, loggerFields);
    }

    private class FieldFormatter {

        private final Map<String, List<PatternFormatter>> delegateMap;
        private final boolean discardIfEmpty;

        public FieldFormatter(final Map<String, List<PatternFormatter>> fieldMap, final boolean discardIfEmpty) {
            this.discardIfEmpty = discardIfEmpty;
            this.delegateMap = fieldMap;
        }

        public StructuredDataElement format(final LogEvent event) {
            final Map<String, String> map = new HashMap<>(delegateMap.size());

            for (final Map.Entry<String, List<PatternFormatter>> entry : delegateMap.entrySet()) {
                final StringBuilder buffer = new StringBuilder();
                for (final PatternFormatter formatter : entry.getValue()) {
                    formatter.format(event, buffer);
                }
                map.put(entry.getKey(), buffer.toString());
            }
            return new StructuredDataElement(map, eventPrefix, discardIfEmpty);
        }
    }

    private class StructuredDataElement {

        private final Map<String, String> fields;
        private final boolean discardIfEmpty;
        private final String prefix;

        public StructuredDataElement(final Map<String, String> fields, final String prefix,
                                     final boolean discardIfEmpty) {
            this.discardIfEmpty = discardIfEmpty;
            this.fields = fields;
            this.prefix = prefix;
        }

        boolean discard() {
            if (discardIfEmpty == false) {
                return false;
            }
            boolean foundNotEmptyValue = false;
            for (final Map.Entry<String, String> entry : fields.entrySet()) {
                if (Strings.isNotEmpty(entry.getValue())) {
                    foundNotEmptyValue = true;
                    break;
                }
            }
            return !foundNotEmptyValue;
        }

        void union(final Map<String, String> addFields) {
            this.fields.putAll(addFields);
        }

        Map<String, String> getFields() {
            return this.fields;
        }

        String getPrefix() {
            return prefix;
        }
    }

    public Facility getFacility() {
        return facility;
    }
}
