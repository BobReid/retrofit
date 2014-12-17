package retrofit;

import java.util.concurrent.Executor;

/**
 * Created by Bob on 14-12-17.
 */
public class AsyncHandlerStrategy implements RestHandlerStrategy {

    private final Executor httpExecutor;
    private final Executor callbackExecutor;
    private final ErrorHandler errorHandler;

    public AsyncHandlerStrategy(Executor httpExecutor, Executor callbackExcutor, ErrorHandler errorHandler) {
        this.httpExecutor = httpExecutor;
        this.callbackExecutor = callbackExcutor;
        this.errorHandler = errorHandler;
    }

    private void assertConfigured() {
        if(httpExecutor == null || callbackExecutor == null)
            throw new IllegalStateException("Asynchronous invocation requires calling setExecutors.");
    }

    @Override
    public boolean canHandleMethod(RestMethodInfo methodInfo) {
        return false;
    }

    @Override
    public Object handleRequest(final RestRequest request) throws Throwable {
        assertConfigured();

        // Apply the interceptor synchronously, recording the interception so we can replay it later.
        // This way we still defer argument serialization to the background thread.
        final RequestInterceptorTape interceptorTape = new RequestInterceptorTape();
        request.getRequestInterceptor().intercept(interceptorTape);

        Callback<?> callback = (Callback<?>) request.getArgs()[request.getArgs().length - 1];
        httpExecutor.execute(new CallbackRunnable(callback, callbackExecutor, errorHandler) {
            @Override
            public ResponseWrapper obtainResponse() {
                //swap the requestInterceptor with interceptorTape
                RestRequest newRequest = new RestRequest(request.getServer(),
                        request.getConverter(),
                        request.getClient(),
                        interceptorTape,
                        request.getMethodInfo(),
                        request.getArgs(),
                        request.getLogLevel(),
                        request.getLog());
                return (ResponseWrapper) newRequest.invoke();
            }
        });

        return null; // Asynchronous methods should have return type of void.
    }
}
