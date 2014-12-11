package com.workable.honeybadger;

import org.glassfish.jersey.client.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Facade giving a simple interface for sending error to the Honeybadger API
 */
public class HoneybadgerClient {

    /**
     * System property key identifying the Honeybadger URL to use.
     */
    public static final String HONEYBADGER_URL_SYS_PROP_KEY =
        "honeybadger.url";

    /**
     * System property key identifying the Honeybadger API key to use.
     */
    public static final String HONEYBADGER_API_KEY_SYS_PROP_KEY =
        "honeybadger.api_key";

    /**
     * Comma delimited list of system properties to not include.
     */
    public static final String HONEYBADGER_EXCLUDED_PROPS_SYS_PROP_KEY =
        "honeybadger.excluded_sys_props";

    /**
     * Comma delimited list of exception classes to ignore.
     */
    public static final String HONEYBADGER_EXCLUDED_CLASSES_SYS_PROP_KEY =
        "honeybadger.excluded_exception_classes";

    /**
     * The default Honebadger URL
     */
    public static final String DEFAULT_API_URI =
        "https://api.honeybadger.io/v1/notices";

    /**
     * If <code>true</code> errors are dispatched asynchronously (Default true)
     */
    private boolean async = true;

    /**
     * Max threads for asynchronous error dispatching. (Default: one)
     */
    private int maxThreads = 1;

    /**
     * The thread priority of the asynchronous thread dispatchers. (Default Thread.MIN)
     */
    private int priority = Thread.MIN_PRIORITY;

    /**
     * The queue size of the asynchronous dispatching mechanism (Default: Integer.MAX)
     */
    private int queueSize = Integer.MAX_VALUE;


    /**
     * Timeout of the {@link #executorService}.
     */
    private static final long SHUTDOWN_TIMEOUT = TimeUnit.SECONDS.toMillis(10);
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String apiKey;

    private final Set<String> excludedExceptionClasses;

    private final JsonMarshaller marshaller;

    /**
     * Executor service in charge of sending errors asynchronously
     */
    private volatile ExecutorService executorService;

    /**
     * Default Constructor. <p>Options can be set via System properties</p>
     */
    public HoneybadgerClient() {
        this(System.getProperty(HONEYBADGER_API_KEY_SYS_PROP_KEY));
    }

    /**
     * Constructs a Client with the specified apiKey
     *
     * @param apiKey The Honeybadger API Key
     */
    public HoneybadgerClient(String apiKey) {
        this(apiKey, System.getProperty(HONEYBADGER_EXCLUDED_PROPS_SYS_PROP_KEY),
             System.getProperty(HONEYBADGER_EXCLUDED_CLASSES_SYS_PROP_KEY));
    }

    /**
     * Constructs a Client with specific options
     *
     * @param apiKey                   The Honeybadger API Key
     * @param excludedSysProps         Comma delimited list of System properties that should be excluded from errors
     *                                 dispatched
     * @param excludedExceptionClasses Comma delimited list of Exceptions that should be ignored
     */
    public HoneybadgerClient(String apiKey, String excludedSysProps, String excludedExceptionClasses) {
        this.apiKey = apiKey;
        this.excludedExceptionClasses = buildExcludedExceptionClasses(excludedExceptionClasses);

        this.marshaller = new JsonMarshaller(buildExcludedSysProps(excludedSysProps));
    }

    /**
     * Reports the specific error to the Honebadger
     */
    public void reportError(Error error) {
        if (async) {
            if (executorService == null) {
                synchronized (this) {
                    if (executorService == null) {
                        initExecutorService();
                    }
                }
            }

            executorService.submit(new EventDispatcher(error));
        } else {
            doDispatchError(error);
        }
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public void setQueueSize(int queueSize) {
        if (queueSize > 0) {
            this.queueSize = queueSize;
        }
    }

    public void setMaxThreads(int maxThreads) {
        if (maxThreads > 0) {
            this.maxThreads = maxThreads;
        }
    }

    public void setPriority(int priority) {
        if (priority > 0) {
            this.priority = priority;
        }
    }


    /**
     * Marshals and dispatched the specified error to the Honeybadger
     */
    protected void doDispatchError(Error error) {

        final String errorClassName = error.getClass().getName();
        if (errorClassName != null &&
            excludedExceptionClasses.contains(errorClassName)) {
            return;
        }

        for (int retries = 0; retries < 3; retries++) {
            try {
                String json = marshaller.marshall(error);
                int responseCode = sendToHoneybadger(json).getStatus();

                if (responseCode != 201) {
                    logger.error("Honeybadger did not respond with the " +
                                 "correct code. Response was [{}]. Retries={}",
                                 responseCode, retries);
                } else {
                    logger.debug("Honeybadger logged error correctly: {}",
                                 error);
                    return;
                }
            } catch (IOException e) {
                String msg = String.format("There was an error when trying " +
                                           "to send the error to " +
                                           "Honeybadger. Retries=%d", retries);
                logger.error(msg, new HoneybadgerException(e));

            }
        }
    }


    /**
     * Send an error encoded in JSON to the Honeybadger API.
     *
     * @param jsonError Error JSON payload
     * @return Status code from the Honeybadger API
     * @throws IOException thrown when a network was encountered
     */
    private Response sendToHoneybadger(String jsonError) throws IOException {
        URI honeybadgerUrl = honeybadgerUrl();

        Client client = ClientBuilder.newClient(new ClientConfig());

        return client
            .target(honeybadgerUrl)
            .request()
            .accept(MediaType.APPLICATION_JSON)
            .header("X-API-Key", apiKey)
            .post(Entity.entity(jsonError, MediaType.APPLICATION_JSON));
    }


    /**
     * Finds the Honeybadger endpoint to send erros to.
     *
     * @return the default URL unless it is overriden by a system property
     */
    private URI honeybadgerUrl() {
        try {
            final String url;
            final String sysProp =
                System.getProperty(HONEYBADGER_URL_SYS_PROP_KEY);

            if (sysProp != null) {
                url = sysProp;
            } else {
                url = DEFAULT_API_URI;
            }

            return URI.create(url);
        } catch (IllegalArgumentException e) {
            String format = "Honeybadger URL was not correctly formed. " +
                            "Double check the [%s] system property and " +
                            "verify that it is a valid URL.";
            String msg = String.format(format, HONEYBADGER_URL_SYS_PROP_KEY);

            throw new HoneybadgerException(msg, e);
        }
    }

    /**
     * Initializes the Executor service used for async error dispatching
     */
    private void initExecutorService() {

        BlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(queueSize);

        final ExecutorService executorService = new ThreadPoolExecutor(
            maxThreads, maxThreads, 0L, TimeUnit.MILLISECONDS, queue,
            new DaemonThreadFactory(priority), new ThreadPoolExecutor.DiscardOldestPolicy());

        this.executorService = executorService;

        Runtime.getRuntime().addShutdownHook(new Thread() {

            public void run() {
                executorService.shutdown();

                try {
                    if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS)) {
                        logger.warn("Graceful shutdown took too much time, forcing the shutdown.");
                        List<Runnable> tasks = executorService.shutdownNow();
                        logger.info("{} tasks failed to execute before the shutdown.", tasks.size());
                    }
                } catch (InterruptedException e) {
                    logger.error("Graceful shutdown interrupted, forcing the shutdown.");
                    List<Runnable> tasks = executorService.shutdownNow();
                    logger.info("{} tasks failed to execute before the shutdown.", tasks.size());
                }

            }

        });
    }

    private Set<String> buildExcludedSysProps(String excluded) {
        HashSet<String> set = new HashSet<>();

        set.add(HONEYBADGER_API_KEY_SYS_PROP_KEY);
        set.add(HONEYBADGER_EXCLUDED_PROPS_SYS_PROP_KEY);
        set.add(HONEYBADGER_URL_SYS_PROP_KEY);

        if (excluded == null || excluded.isEmpty()) {
            return set;
        }

        for (String item : excluded.split(",")) {
            set.add(item);
        }

        return set;
    }

    private Set<String> buildExcludedExceptionClasses(String excluded) {
        HashSet<String> set = new HashSet<>();

        set.add(HoneybadgerException.class.getCanonicalName());

        if (excluded == null || excluded.isEmpty()) {
            return set;
        }

        for (String item : excluded.split(",")) {
            set.add(item);
        }

        return set;
    }


    /**
     * Runnable to dispatch asynchronously errors
     */
    private final class EventDispatcher implements Runnable {

        private final Error error;

        public EventDispatcher(Error error) {
            this.error = error;
        }

        @Override
        public void run() {
            try {
                doDispatchError(error);
            } catch (Exception e) {
                logger.error("An exception occurred while dispatching the error", new HoneybadgerException(e));
            }
        }
    }


}
