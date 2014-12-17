package retrofit;

public interface RestHandlerStrategy {

    boolean canHandleMethod(RestMethodInfo methodInfo);
    Object handleMethod(RestMethodInfo methodInfo);

}
