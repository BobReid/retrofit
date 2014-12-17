package retrofit;

public interface RestHandlerStrategy {

    boolean canHandleMethod(RestMethodInfo methodInfo);
    Object handleRequest(RestRequest request) throws Throwable;
}
