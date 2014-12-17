package retrofit;

import java.util.concurrent.Executor;

/**
 * Created by Bob on 14-12-17.
 */
public class ObservableHandlerStrategy implements RestHandlerStrategy {

    private final RequestInterceptor requestInterceptor;
    private final Executor httpExecutor;
    private final ErrorHandler errorHandler;

    private RxSupport rxSupport;

    public ObservableHandlerStrategy(RequestInterceptor requestInterceptor, Executor httpExecutor, ErrorHandler errorHandler) {
        this.requestInterceptor = requestInterceptor;
        this.httpExecutor = httpExecutor;
        this.errorHandler = errorHandler;
    }

    private RxSupport getRxSupport() {
        if(rxSupport == null) {
            rxSupport = new RxSupport(httpExecutor, errorHandler, requestInterceptor);
        }
        return rxSupport;
    }

    private void assertConfigured() {
        if(httpExecutor == null) {
            throw new IllegalStateException("Asynchronous invocation requires calling setExecutors.");
        }
        if(Platform.HAS_RX_JAVA) {
            throw new IllegalStateException("Observable method found but no RxJava on classpath.");
        }
    }

    @Override
    public boolean canHandleMethod(RestMethodInfo methodInfo) {
        return methodInfo.isObservable;
    }

    @Override
    public Object handleRequest(final RestRequest request) throws Throwable {
        assertConfigured();
        return getRxSupport().createRequestObservable(new RxSupport.Invoker() {
            @Override
            public ResponseWrapper invoke(RequestInterceptor requestInterceptor) {
                return (ResponseWrapper) request.invoke();
            }
        });
    }
}
