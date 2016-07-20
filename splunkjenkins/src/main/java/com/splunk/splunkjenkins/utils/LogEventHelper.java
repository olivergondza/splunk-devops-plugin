package com.splunk.splunkjenkins.utils;

import com.google.common.collect.ImmutableMap;
import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.splunk.splunkjenkins.SplunkJenkinsInstallation;
import com.splunk.splunkjenkins.model.EventRecord;
import com.splunk.splunkjenkins.model.EventType;
import hudson.FilePath;
import hudson.Util;
import hudson.console.ConsoleNote;
import hudson.model.*;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.node_monitors.NodeMonitor;
import hudson.util.ByteArrayOutputStream2;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Strings.emptyToNull;
import static com.splunk.splunkjenkins.Constants.LOG_TIME_FORMAT;
import static org.apache.commons.lang.reflect.MethodUtils.getAccessibleMethod;

public class LogEventHelper {
    public static String NODE_NAME = "node_name";
    public final static String SLAVE_TAG_NAME = "slave";
    //see also hudson.util.wrapToErrorSpan
    private static final Pattern ERROR_SPAN_CONTENT = Pattern.compile("error.*?>(.*?)</span>", Pattern.CASE_INSENSITIVE);
    public static final String SEPARATOR = "    ";
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(LogEventHelper.class.getName());
    private static final String channel = UUID.randomUUID().toString().toUpperCase();
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().setFieldNamingStrategy(new LowerCaseStrategy()).create();
    private static final Map<String, Long> HUMAN_READABLE_SIZE = ImmutableMap.<String, Long>builder()
            .put("KB", 1024L)
            .put("kB", 1000L)
            .put("KiB", 1024L)
            .put("MB", 1024 * 1024L)
            .put("MiB", 1024 * 1024L)
            .put("GB", 1024 * 1024 * 1024L)
            .build();

    public static HttpPost buildPost(EventRecord record, SplunkJenkinsInstallation config) {
        HttpPost postMethod = new HttpPost(record.getEndpoint(config));
        if (config.isMetaDataInURLSupported(record.getEventType().needSplit())) {
            postMethod.setEntity(new StringEntity(record.getMessageString(), "utf-8"));
        } else {
            //http event collector does not support raw event, need split records and append metadata to message body
            String jsonRecord;
            if (record.getEventType().needSplit()) {
                StringWriter stout = new StringWriter();
                String[] values = record.getMessageString().split("[\\r\\n]+");
                for (String line : values) {
                    if (line != "") {
                        EventRecord lineRecord = new EventRecord(line, record.getEventType());
                        lineRecord.setSource(record.getSource());
                        lineRecord.setTime(record.getTime());
                        stout.write(gson.toJson(lineRecord.toMap(config)));
                        stout.write("\n");
                    }
                }
                jsonRecord = stout.toString();
            } else {
                jsonRecord = gson.toJson(record.toMap(config));
            }
            StringEntity entity = new StringEntity(jsonRecord, "utf-8");
            entity.setContentType("application/json; profile=urn:splunk:event:1.0; charset=utf-8");
            postMethod.setEntity(entity);
        }
        postMethod.setHeader("x-splunk-request-channel", channel);
        postMethod.setHeader("Authorization", "Splunk " + config.getToken());
        return postMethod;
    }

    public static FormValidation verifyHttpInput(SplunkJenkinsInstallation config) {
        HttpPost post = buildPost(new EventRecord("ping from jenkins plugin", EventType.GENERIC_TEXT), config);
        HttpClient client = SplunkLogService.getInstance().getClient();
        try {
            HttpResponse response = client.execute(post);
            if (response.getStatusLine().getStatusCode() != 200) {
                String reason = response.getStatusLine().getReasonPhrase();
                if (response.getStatusLine().getStatusCode() == 400) {
                    return FormValidation.error("incorrect index name or do not have write permission to the default index, please check MetaData configuration");
                } else {
                    return FormValidation.error("token:" + config.getToken() + " response:" + reason);
                }
            }
            EntityUtils.consume(response.getEntity());
            //check if raw events is supported
            config.setRawEventEnabled(true);
            post = buildPost(new EventRecord("ping from jenkins plugin\nraw event ping", EventType.GENERIC_TEXT), config);
            response = client.execute(post);
            SplunkJenkinsInstallation globalConfig = SplunkJenkinsInstallation.get();
            if (response.getStatusLine().getStatusCode() != 200 && globalConfig != null) {
                //it is ok to use json but update global flag to turn off raw handling
                SplunkJenkinsInstallation.get().setRawEventEnabled(false);
                return FormValidation.ok("Splunk connection verified but raw event is not supported");
            }
        } catch (IOException e) {
            return FormValidation.error(e.getMessage());
        }
        return FormValidation.ok("Splunk connection verified");
    }

    /**
     * the logical extracted from PlainTextConsoleOutputStream
     * console annotation will be removed, e.g.
     * Input:Started by user ESC[8mha:AAAAlh+LCAAAAAAAAP9b85aBtbiIQTGjNKU4P08vOT+vOD8nVc83PyU1x6OyILUoJzMv2y+/JJUBAhiZGBgqihhk0NSjKDWzXb3RdlLBUSYGJk8GtpzUvPSSDB8G5tKinBIGIZ+sxLJE/ZzEvHT94JKizLx0a6BxUmjGOUNodHsLgAzOEgYu/dLi1CL9vNKcHACFIKlWvwAAAA==ESC[0manonymous
     * Output:Started by user anonymous
     *
     * @param in     the byte array
     * @param length how many bytes we want to read in
     * @param out    write max(length) to out
     * @see hudson.console.PlainTextConsoleOutputStream
     */
    public static void decodeConsoleBase64Text(byte[] in, int length, ByteArrayOutputStream2 out) {
        int next = ConsoleNote.findPreamble(in, 0, length);

        // perform byte[]->char[] while figuring out the char positions of the BLOBs
        int written = 0;
        while (next >= 0) {
            if (next > written) {
                out.write(in, written, next - written);
                written = next;
            } else {
                assert next == written;
            }

            int rest = length - next;
            ByteArrayInputStream b = new ByteArrayInputStream(in, next, rest);

            try {
                ConsoleNote.skip(new DataInputStream(b));
            } catch (IOException ex) {
                Logger.getLogger(LogEventHelper.class.getName()).log(Level.SEVERE, "failed to filter blob", ex);
            }

            int bytesUsed = rest - b.available(); // bytes consumed by annotations
            written += bytesUsed;


            next = ConsoleNote.findPreamble(in, written, length - written);
        }
        // finish the remaining bytes->chars conversion
        out.write(in, written, length - written);
    }

    public static boolean nonEmpty(String value) {
        return emptyToNull(value) != null;
    }

    public static String getUserName() {
        User user = User.current();
        if (user == null) {
            return "anonymous";
        } else {
            return user.getDisplayName();
        }
    }

    /**
     * @return Queue statics with timestamp
     */
    public static Map<String, Object> getQueueInfo() {
        Jenkins instance = Jenkins.getInstance();
        int computerSize = instance.getComputers().length;
        int totalExecutors = instance.overallLoad.computeTotalExecutors();
        int queueLength = instance.overallLoad.computeQueueLength();
        int idleExecutors = instance.overallLoad.computeIdleExecutors();
        SimpleDateFormat sdf = new SimpleDateFormat(LOG_TIME_FORMAT, Locale.US);
        Map<String, Object> event = new HashMap<>();
        event.put("queue_length", queueLength);
        event.put("total_computers", computerSize);
        event.put("idle_executors", idleExecutors);
        event.put("total_executors", totalExecutors);
        event.put("time", sdf.format(new Date()));
        return event;
    }

    public static int sendFiles(AbstractBuild build, Map<String, String> envVars, TaskListener listener,
                                String includes, String excludes, boolean sendFromSlave, long maxFileSize) {
        FilePath ws = build.getWorkspace();
        int eventCount = 0;
        final String expanded = Util.replaceMacro(includes, envVars);
        final String exclude = Util.replaceMacro(excludes, envVars);
        try {
            final FilePath[] paths = ws.list(expanded, exclude);
            if (paths.length == 0) {
                LOG.warning("can not find files using includes:" + includes + " excludes:" + excludes + " in workspace:" + ws.getRemote());
                return eventCount;
            }
            Map configMap = SplunkJenkinsInstallation.get().toMap();
            LogFileCallable fileCallable = new LogFileCallable(ws.getRemote(), build.getUrl(), configMap, sendFromSlave, maxFileSize);
            eventCount = fileCallable.sendFiles(paths);
            listener.getLogger().println("sent " + Arrays.toString(paths) + " to splunk in " + eventCount + " events");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "failed to archive files", e);
        } catch (InterruptedException e) {
            LOG.log(Level.SEVERE, "interrupted while archiving file", e);
        }
        return eventCount;
    }

    public static long parseFileSize(String size) {
        if (emptyToNull(size) == null) {
            return 0;
        }
        try {
            for (String key : HUMAN_READABLE_SIZE.keySet()) {
                if (size.endsWith(key)) {
                    String numberPart = size.substring(0, size.length() - key.length());
                    if (numberPart.contains(".")) {
                        return new Float(HUMAN_READABLE_SIZE.get(key) * Float.parseFloat(numberPart)).longValue();
                    }
                    return HUMAN_READABLE_SIZE.get(key) * Long.parseLong(numberPart);
                }
            }
            return Long.parseLong(size);
        } catch (NumberFormatException ex) {
            LOG.log(Level.SEVERE, "invalid number " + size);
            return 0;
        }
    }

    public static class UrlQueryBuilder {
        private Map<String, String> query = new HashMap();

        public static String toString(Map<String, String> queryParameters) {
            StringBuilder stringBuilder = new StringBuilder();
            for (String key : queryParameters.keySet()) {
                stringBuilder.append(key)
                        .append("=")
                        .append(URLEncoder.encode(queryParameters.get(key)))
                        .append("&");
            }
            if (stringBuilder.length() == 0) {
                return "";
            }
            return stringBuilder.substring(0, stringBuilder.length() - 1);
        }

        public UrlQueryBuilder putIfAbsent(String key, String value) {
            if (nonEmpty(value) && !"null".equals(value)) {
                //Map.putIfAbsent was @since 1.8, use get and check null to check
                Object existValue = query.get(key);
                if (existValue == null) {
                    query.put(key, value);
                }
            }
            return this;
        }

        public Map getQueryMap() {
            return Collections.unmodifiableMap(query);
        }

        public String build() {
            return UrlQueryBuilder.toString(this.query);
        }
    }

    static class LowerCaseStrategy implements FieldNamingStrategy {
        @Override
        public String translateName(final Field f) {
            return f.getName().toLowerCase();
        }
    }

    private static Map<String, Object> getComputerStatus(Computer computer) {
        String nodeName;
        Map slaveInfo = new HashMap();
        if (computer instanceof Jenkins.MasterComputer) {
            nodeName = "(master)";
        } else {
            nodeName = computer.getName();
        }
        slaveInfo.put(NODE_NAME, nodeName);
        slaveInfo.put("tag", SLAVE_TAG_NAME);
        Node slaveNode = computer.getNode();
        if (slaveNode != null) {
            slaveInfo.put("label", slaveNode.getLabelString());
        }
        slaveInfo.put("status", "updated");
        slaveInfo.put("num_executors", computer.getNumExecutors());
        slaveInfo.put("is_idle", computer.isIdle());
        slaveInfo.put("is_online", computer.isOnline());
        slaveInfo.put("url",Jenkins.getInstance().getRootUrl()+computer.getUrl());
        long connectTime = computer.getConnectTime();
        if (connectTime != 0) {
            slaveInfo.put("connect_time", Util.XS_DATETIME_FORMATTER.format(connectTime));
        } else {
            //slave is offline or disconnected
            slaveInfo.put("connect_time", 0);
        }
        if (!computer.isIdle()) {
            List<String> builds = new ArrayList<>();
            for (Executor executor : computer.getExecutors()) {
                if (executor.isBusy()) {
                    if (executor.isBusy() && executor.getCurrentExecutable() instanceof Run) {
                        Run run = (Run) executor.getCurrentExecutable();
                        if (run != null) {
                            builds.add(run.getUrl());
                        }
                    }
                }
            }
            if (!builds.isEmpty()) {
                slaveInfo.put("running_builds", builds);
            }
        }
        Method method = getAccessibleMethod(computer.getClass(), "getUptime", new Class<?>[0]);
        if (method != null) {
            try { //cloud slave may defined getUptime method
                Object uptime = method.invoke(computer, new Object[0]);
                slaveInfo.put("uptime", uptime);
            } catch (Exception e) {
                //just ignore
            }
        }
        return slaveInfo;
    }

    private static Map<String, Object> getMonitorData(Computer computer, NodeMonitor monitor) {
        Map monitorStatus = new HashMap();
        Object data = monitor.data(computer);
        if (data != null) {
            String monitorName = monitor.getClass().getSimpleName();
            //Jenkins monitors are designed for web pages, toString() OR toHtml may contain html code
            String monitorData;
            Method method = getAccessibleMethod(data.getClass(), "toHtml", new Class<?>[0]);
            if (method != null) {
                try {
                    monitorData = (String) method.invoke(data, new Object[0]);
                } catch (Exception e) {
                    monitorData = data.toString();
                }
            } else {
                monitorData = data.toString();
            }
            Matcher matcher = ERROR_SPAN_CONTENT.matcher(monitorData);
            if (matcher.find()) {
                monitorStatus.put(monitorName, "warning:" + matcher.group(1));
            } else {
                monitorStatus.put(monitorName, monitorData);
            }
        }
        return monitorStatus;
    }

    /**
     * @return a map with slave name as key and monitor result as value
     * monitor result is a map with monitor name as key, monitor data as value
     */
    public static Map<String, Map<String, Object>> getSlaveStats() {
        Map<String, Map<String, Object>> slaveStatusMap = new HashMap<>();
        Computer[] computers = Jenkins.getInstance().getComputers();
        if (computers == null || computers.length == 0) {
            return slaveStatusMap;
        }
        Collection<NodeMonitor> monitors = ComputerSet.getMonitors();
        for (Computer computer : computers) {
            Map slaveInfo = new HashMap();
            slaveInfo.putAll(getComputerStatus(computer));
            for (NodeMonitor monitor : monitors) {
                slaveInfo.putAll(getMonitorData(computer, monitor));
            }
            slaveStatusMap.put((String) slaveInfo.get(NODE_NAME), slaveInfo);
        }
        return slaveStatusMap;
    }
}