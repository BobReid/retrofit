package retrofit;

/**
 * Created by Bob on 14-12-16.
 */
public class SynchronousHandlerStategy  implements RestHandlerStrategy {

    @Override
    public boolean canHandleMethod(RestMethodInfo methodInfo) {
        return methodInfo.isSynchronous;
    }

    @Override
    public Object handleMethod(RestMethodInfo methodInfo) {
        return null;
    }
}
