package com.workable.honeybadger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.workable.honeybadger.servlet .HttpServletRequestInfoGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * Utility class responsible to serialize to json compatible with the Honeybadger API.
 */
public class JsonMarshaller {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final String hostname;
    private final String runtimeRoot;
    private final Set<String> excludedSysProps;

    /**
     *
     * @param excludedSysProps
     */
    public JsonMarshaller(Set<String> excludedSysProps) {
        this.hostname = hostname();
        this.runtimeRoot = runtimeRoot();
        this.excludedSysProps = excludedSysProps;
    }

    public String marshall(Error error) {

        Gson myGson = new Gson();
        JsonObject jsonError = new JsonObject();
        jsonError.add("notifier", makeNotifier());
        jsonError.add("error", makeError(error));
        jsonError.add("request", makeRequest(error));

        jsonError.add("server", makeServer());

        return myGson.toJson(jsonError);
    }

    private JsonObject makeRequest(Error error) {

        JsonObject request = new JsonObject();

        try {
            Class.forName("javax.servlet.http.HttpServletRequest");
            RequestInfoGenerator<?> generator =
                new HttpServletRequestInfoGenerator();
            request = generator.routeRequest(error.getContext());
        } catch (ClassNotFoundException e) {
            // silent catch
        }

        request.add("context", context());
        request.addProperty("component", error.getReporter());
        request.addProperty("action", methodName(error.getError()));

        return request;
    }

    /*
      Format the throwable into a json object
    */
    private JsonObject makeError(Error error) {

        Throwable throwable = error.getError();

        JsonObject jsonError = new JsonObject();
        jsonError.addProperty("class", throwable.getClass().getName());
        jsonError.addProperty("message", error.getMessage() == null ? throwable.getMessage() : error.getMessage());

        JsonArray backTrace = new JsonArray();
        populateTraceElements(throwable, backTrace, false);

        if (throwable.getCause() != null){
            JsonObject causedByElement = traceElement(0, "Caused by: " + throwable.getCause().getClass().getName() + ": " + throwable.getCause().getMessage(), ".");
            backTrace.add(causedByElement);
            populateTraceElements(throwable.getCause(), backTrace, true);
        }

        jsonError.add("backtrace", backTrace);

        JsonObject sourceElement = new JsonObject();

        appendStacktraceToJsonElement(throwable, sourceElement);

        jsonError.add("source", sourceElement);

        return jsonError;
    }

    private void populateTraceElements(Throwable throwable, JsonArray backTrace, boolean cause){

        int count = 0;
        for (StackTraceElement trace : throwable.getStackTrace()) {

            String fileName = (cause ? "  " : "") + trace.getFileName();
            JsonObject jsonTraceElement = traceElement(trace.getLineNumber(),
                                                       fileName,
                                                       String.format("%s.%s",trace.getClassName(), trace.getMethodName()));
            if (cause && ++count == 3){
                backTrace.add(traceElement(0, String.format("... %d more", throwable.getStackTrace().length - count), "." ));
                break;
            }
            backTrace.add(jsonTraceElement);
        }
    }

    private String methodName(Throwable throwable){
        if (throwable == null || throwable.getStackTrace() == null || throwable.getStackTrace().length == 0){
            return null;
        }
        return throwable.getStackTrace()[0].getMethodName();
    }

    private JsonObject traceElement(int line, String file, String method){
        JsonObject jsonTraceElement = new JsonObject();
        if (line > 0){
            jsonTraceElement.addProperty("number", line);
        }
        if (file != null){
            jsonTraceElement.addProperty("file", file);
        }
        if (method != null){
            jsonTraceElement.addProperty("method", method);
        }
        return jsonTraceElement;
    }

    /*
      Identify the notifier
    */
    private JsonObject makeNotifier() {
        JsonObject notifier = new JsonObject();
        notifier.addProperty("name", "workable-honeybadger-java");
        notifier.addProperty("version", "1.3.0");
        return notifier;
    }


    private String stacktraceAsString(Throwable error) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw, true);
        error.printStackTrace(pw);
        return sw.getBuffer().toString();
    }

    /**
     * Created a Honeybadger source blob compatible full stacktrace.
     */
    private void appendStacktraceToJsonElement(Throwable error,
                                               JsonObject json) {
        String stack = stacktraceAsString(error);
        Scanner scanner = new Scanner(stack);

        int lineNo = 0;

        while (scanner.hasNext()) {
            json.addProperty(String.valueOf(++lineNo), scanner.nextLine());
        }
    }

    private JsonObject makeServer() {
        JsonObject jsonServer = new JsonObject();
        jsonServer.addProperty("environment_name", environment());
        jsonServer.addProperty("hostname", hostname);
        jsonServer.addProperty("runtime_root", runtimeRoot);
        jsonServer.add("system_properties", systemProperties());

        return jsonServer;
    }

    private JsonObject context() {
        JsonObject context = new JsonObject();

        @SuppressWarnings("unchecked")
        Map<String, String> mdc = MDC.getCopyOfContextMap();

        if (mdc != null) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                context.addProperty(entry.getKey(), entry.getValue());
            }
        }

        return context;
    }

    private JsonObject systemProperties() {
        JsonObject jsonSysProps = new JsonObject();

        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            // We skip all excluded properties
            if (excludedSysProps.contains(entry.getKey().toString())) {
                continue;
            }

            jsonSysProps.addProperty(entry.getKey().toString(),
                                     entry.getValue().toString());
        }

        return jsonSysProps;
    }

    /**
     * Finds the name of the environment by looking at a few common Java system properties and/or environment
     * variables.
     *
     * @return the name of the environment, otherwise "development"
     */
    private String environment() {
        String sysPropJavaEnv = System.getProperty("JAVA_ENV");
        if (sysPropJavaEnv != null) {
            return sysPropJavaEnv;
        }

        String javaEnv = System.getenv("JAVA_ENV");
        if (javaEnv != null) {
            return javaEnv;
        }

        String sysPropEnv = System.getProperty("ENV");
        if (sysPropEnv != null) {
            return sysPropEnv;
        }

        String env = System.getenv("ENV");
        if (sysPropEnv != null) {
            return env;
        }

        // If no system property defined, then return development
        return "development";
    }

    private String hostname() {
        String host;

        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            logger.error("Unable to find hostname", e);
            host = "unknown";
        }

        return host;
    }

    private String runtimeRoot() {
        try {
            return (new File(".")).getCanonicalPath();
        } catch (IOException e) {
            logger.error("Can't get runtime root path", e);
            return "unknown";
        }
    }


}
