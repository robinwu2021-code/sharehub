package ai.neargo.opskit.svc.web;

/** 同键同内容的重复写请求：原样回放首次的状态码与响应体（由 {@link ApiErrorHandler} 写出，不再执行业务）。 */
public final class IdempotentReplay extends RuntimeException {

    private final int status;
    private final String body;

    public IdempotentReplay(int status, String body) {
        super(null, null, false, false);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
