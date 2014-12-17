package retrofit;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;

import retrofit.client.Client;
import retrofit.client.Header;
import retrofit.client.Request;
import retrofit.client.Response;
import retrofit.converter.ConversionException;
import retrofit.converter.Converter;
import retrofit.mime.MimeUtil;
import retrofit.mime.TypedByteArray;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedOutput;

/**
 * Created by Bob on 14-12-16.
 */
public class RestRequest {

    private final Endpoint server;
    private final Converter converter;
    private final Client client;
    private final RequestInterceptor requestInterceptor;
    private final RestMethodInfo methodInfo;
    private final Object[] args;
    private final RestAdapter.Log log;
    private final RestAdapter.LogLevel logLevel;

    public RestRequest(Endpoint server, Converter converter, Client client, RequestInterceptor requestInterceptor,
                       RestMethodInfo methodInfo, Object[] args, RestAdapter.LogLevel logLevel, RestAdapter.Log log) {
        this.server = server;
        this.converter = converter;
        this.client = client;
        this.requestInterceptor = requestInterceptor;
        this.methodInfo = methodInfo;
        this.args = args;
        this.logLevel = logLevel;
        this.log = log;
    }

    public Endpoint getServer() {
        return server;
    }

    public Converter getConverter() {
        return converter;
    }

    public Client getClient() {
        return client;
    }

    public RequestInterceptor getRequestInterceptor() {
        return requestInterceptor;
    }

    public RestMethodInfo getMethodInfo() {
        return methodInfo;
    }

    public Object[] getArgs() {
        return args;
    }

    public RestAdapter.LogLevel getLogLevel() {
        return logLevel;
    }

    public RestAdapter.Log getLog() {
        return log;
    }

    /**
     * Execute an HTTP request.
     *
     * @return HTTP response object of specified {@code type} or {@code null}.
     * @throws RetrofitError if any error occurs during the HTTP request.
     */
    public Object invoke() {
        String url = null;
        try {

            methodInfo.init(); // Ensure all relevant method information has been loaded.

            String serverUrl = server.getUrl();
            RequestBuilder requestBuilder = new RequestBuilder(serverUrl, methodInfo, converter);
            requestBuilder.setArguments(args);

            requestInterceptor.intercept(requestBuilder);

            Request request = requestBuilder.build();
            url = request.getUrl();

            if (!methodInfo.isSynchronous) {
                // If we are executing asynchronously then update the current thread with a useful name.
                Thread.currentThread().setName(RestAdapter.THREAD_PREFIX + url.substring(serverUrl.length()));
            }

            if (logLevel.log()) {
                // Log the request data.
                request = logAndReplaceRequest("HTTP", request, args);
            }

            long start = System.nanoTime();
            Response response = client.execute(request);
            long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            if (logLevel.log()) {
                // Log the response data.
                response = logAndReplaceResponse(url, response, elapsedTime);
            }

            Type type = methodInfo.responseObjectType;

            int statusCode = response.getStatus();
            if (statusCode >= 200 && statusCode < 300) { // 2XX == successful request
                // Caller requested the raw Response object directly.
                if (type.equals(Response.class)) {
                    if (!methodInfo.isStreaming) {
                        // Read the entire stream and replace with one backed by a byte[].
                        response = Utils.readBodyToBytesIfNecessary(response);
                    }

                    if (methodInfo.isSynchronous) {
                        return response;
                    }
                    return new ResponseWrapper(response, response);
                }

                TypedInput body = response.getBody();
                if (body == null) {
                    if (methodInfo.isSynchronous) {
                        return null;
                    }
                    return new ResponseWrapper(response, null);
                }

                ExceptionCatchingTypedInput wrapped = new ExceptionCatchingTypedInput(body);
                try {
                    Object convert = converter.fromBody(wrapped, type);
                    logResponseBody(body, convert);
                    if (methodInfo.isSynchronous) {
                        return convert;
                    }
                    return new ResponseWrapper(response, convert);
                } catch (ConversionException e) {
                    // If the underlying input stream threw an exception, propagate that rather than
                    // indicating that it was a conversion exception.
                    if (wrapped.threwException()) {
                        throw wrapped.getThrownException();
                    }

                    // The response body was partially read by the converter. Replace it with null.
                    response = Utils.replaceResponseBody(response, null);

                    throw RetrofitError.conversionError(url, response, converter, type, e);
                }
            }

            response = Utils.readBodyToBytesIfNecessary(response);
            throw RetrofitError.httpError(url, response, converter, type);
        } catch (RetrofitError e) {
            throw e; // Pass through our own errors.
        } catch (IOException e) {
            if (logLevel.log()) {
                logException(e, url);
            }
            throw RetrofitError.networkError(url, e);
        } catch (Throwable t) {
            if (logLevel.log()) {
                logException(t, url);
            }
            throw RetrofitError.unexpectedError(url, t);
        } finally {
            if (!methodInfo.isSynchronous) {
                Thread.currentThread().setName(RestAdapter.IDLE_THREAD_NAME);
            }
        }
    }

    /** Log request headers and body. Consumes request body and returns identical replacement. */
    Request logAndReplaceRequest(String name, Request request, Object[] args) throws IOException {
        log.log(String.format("---> %s %s %s", name, request.getMethod(), request.getUrl()));

        if (logLevel.ordinal() >= RestAdapter.LogLevel.HEADERS.ordinal()) {
            for (Header header : request.getHeaders()) {
                log.log(header.toString());
            }

            String bodySize = "no";
            TypedOutput body = request.getBody();
            if (body != null) {
                String bodyMime = body.mimeType();
                if (bodyMime != null) {
                    log.log("Content-Type: " + bodyMime);
                }

                long bodyLength = body.length();
                bodySize = bodyLength + "-byte";
                if (bodyLength != -1) {
                    log.log("Content-Length: " + bodyLength);
                }

                if (logLevel.ordinal() >= RestAdapter.LogLevel.FULL.ordinal()) {
                    if (!request.getHeaders().isEmpty()) {
                        log.log("");
                    }
                    if (!(body instanceof TypedByteArray)) {
                        // Read the entire response body to we can log it and replace the original response
                        request = Utils.readBodyToBytesIfNecessary(request);
                        body = request.getBody();
                    }

                    byte[] bodyBytes = ((TypedByteArray) body).getBytes();
                    String bodyCharset = MimeUtil.parseCharset(body.mimeType(), "UTF-8");
                    log.log(new String(bodyBytes, bodyCharset));
                } else if (logLevel.ordinal() >= RestAdapter.LogLevel.HEADERS_AND_ARGS.ordinal()) {
                    if (!request.getHeaders().isEmpty()) {
                        log.log("---> REQUEST:");
                    }
                    for (int i = 0; i < args.length; i++) {
                        log.log("#" + i + ": " + args[i]);
                    }
                }
            }

            log.log(String.format("---> END %s (%s body)", name, bodySize));
        }

        return request;
    }


    /** Log response headers and body. Consumes response body and returns identical replacement. */
    private Response logAndReplaceResponse(String url, Response response, long elapsedTime)
            throws IOException {
        log.log(String.format("<--- HTTP %s %s (%sms)", response.getStatus(), url, elapsedTime));

        if (logLevel.ordinal() >= RestAdapter.LogLevel.HEADERS.ordinal()) {
            for (Header header : response.getHeaders()) {
                log.log(header.toString());
            }

            long bodySize = 0;
            TypedInput body = response.getBody();
            if (body != null) {
                bodySize = body.length();

                if (logLevel.ordinal() >= RestAdapter.LogLevel.FULL.ordinal()) {
                    if (!response.getHeaders().isEmpty()) {
                        log.log("");
                    }

                    if (!(body instanceof TypedByteArray)) {
                        // Read the entire response body so we can log it and replace the original response
                        response = Utils.readBodyToBytesIfNecessary(response);
                        body = response.getBody();
                    }

                    byte[] bodyBytes = ((TypedByteArray) body).getBytes();
                    bodySize = bodyBytes.length;
                    String bodyMime = body.mimeType();
                    String bodyCharset = MimeUtil.parseCharset(bodyMime, "UTF-8");
                    log.log(new String(bodyBytes, bodyCharset));
                }
            }

            log.log(String.format("<--- END HTTP (%s-byte body)", bodySize));
        }

        return response;
    }

    private void logResponseBody(TypedInput body, Object convert) {
        if (logLevel.ordinal() == RestAdapter.LogLevel.HEADERS_AND_ARGS.ordinal()) {
            log.log("<--- BODY:");
            log.log(convert.toString());
        }
    }

    /** Log an exception that occurred during the processing of a request or response. */
    void logException(Throwable t, String url) {
        log.log(String.format("---- ERROR %s", url != null ? url : ""));
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log.log(sw.toString());
        log.log("---- END ERROR");
    }

}
