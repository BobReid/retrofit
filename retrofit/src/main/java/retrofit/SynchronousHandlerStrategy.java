package retrofit;

/**
 * Created by Bob on 14-12-16.
 */
public class SynchronousHandlerStrategy implements RestHandlerStrategy {

    private final ErrorHandler errorHandler;
    public SynchronousHandlerStrategy(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    public boolean canHandleMethod(RestMethodInfo methodInfo) {
        return methodInfo.isSynchronous;
    }

    @Override
    public Object handleRequest(RestRequest request) throws Throwable {
        try {
            return request.invoke();
        } catch (RetrofitError error) {
            Throwable newError = errorHandler.handleError(error);
            if (newError == null) {
                throw new IllegalStateException("Error handler returned null for wrapped exception.",
                        error);
            }
            throw newError;
        }
    }
}
