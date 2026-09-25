package ai.neargo.sharehub.common;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.Result;
import ai.neargo.common.core.ServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 兜底异常 → neargo {@link Result} 错误包（复用 common-core 的 {@link ErrorCode}/{@link ServerException}）。
 *
 * <p>保留 powerbank 特定映射：{@link IllegalArgumentException}→400、{@link AccessDeniedException}→403
 * （neargo {@code ServerExceptionHandler} 未覆盖此二者，会落 500，故本处自管以维持 RBAC/状态机语义）。
 */
@RestControllerAdvice(basePackages = "ai.neargo.sharehub")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常 → {@link Result} 错误包，**HTTP 状态由业务码推导**。
     *
     * <p>业务拒绝（「券已领完」「仅 HELD 押金可解冻」）此前一律是裸的
     * {@link IllegalStateException}，落到下面的兜底 ⇒ 500 +「服务器错误」+ 一行带栈的 ERROR。
     * 调用方拿不到真原因，而 ERROR 级别也因此失去意义（券领完不需要任何人介入）。
     * 现在这类改抛 {@code ServerException.of(ErrorCode.CONFLICT, …)} 走这里。
     *
     * <p><b>为什么不恒回 200</b>：neargo 的 {@code Result} 约定把错误放在包里，
     * 但状态码恒 200 会让业务失败在 HTTP 层完全不可见 —— 代理日志、监控、
     * 任何只看状态码的东西都以为一切正常。本仓库已有的 400/403 映射就是这个用意
     * （见类注释「维持 RBAC/状态机语义」），这里保持一致。
     *
     * <p><b>为什么不无脑 {@code HttpStatus.resolve}</b>：{@code Result} 的码域允许业务自定义
     * （不一定是 HTTP 码）。解析不出来、或解析出来不是 4xx/5xx 的，**仍回 200** ——
     * 那是旧行为，别把别人的码域当状态码用。
     */
    @ExceptionHandler(ServerException.class)
    public ResponseEntity<Result<Void>> onServer(ServerException e) {
        // message 约定为 i18n key（非 key 的成品串经 Messages 原样返回，兼容旧代码）
        // BizException 额外带 i18n 占位参数 —— 真实文案大多带值
        // （「仅 FROZEN 可请款，当前：RELEASED」），不传 args 的话 {0} 会原样留在界面上
        Object[] args = e instanceof BizException b ? b.args() : new Object[0];
        return ResponseEntity.status(statusOf(e.getCode()))
                .body(Result.error(e.getCode(), Messages.msg(e.getMessage(), args)));
    }

    /** 业务码 → HTTP 状态；只认 4xx/5xx，其余回 200（见 {@link #onServer} 注释）。 */
    static HttpStatus statusOf(int code) {
        HttpStatus s = HttpStatus.resolve(code);
        return s != null && (s.is4xxClientError() || s.is5xxServerError()) ? s : HttpStatus.OK;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> onBadRequest(IllegalArgumentException e) {
        return Result.error(ErrorCode.BAD_REQUEST.getCode(),
                e.getMessage() == null ? Messages.msg("error.bad_request") : Messages.msg(e.getMessage()));
    }

    /** 方法级 @PreAuthorize 拒绝 → 403（AOP 抛的 AccessDeniedException 不经安全过滤器 deniedHandler）。 */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> onDenied(AccessDeniedException e) {
        return Result.error(ErrorCode.FORBIDDEN.getCode(), Messages.msg("error.forbidden"));
    }

    /** 显式抛的 ResponseStatusException（如登录密码错 → 401） —— 保留其状态与 reason，
     *  否则会被下面兜底的 {@link Exception} handler 抹成 500「服务器错误」。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> onResponseStatus(ResponseStatusException e) {
        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return ResponseEntity.status(e.getStatusCode())
                .body(Result.error(e.getStatusCode().value(), Messages.msg(reason)));
    }

    /**
     * 兜底：未预期异常 → 500。
     *
     * <p><b>必须打日志带堆栈</b>。原实现直接返回「服务器错误」而不记录任何东西 ——
     * 结果是任何 500 都查不出原因：应用日志里没有、maven 输出里也没有，
     * 只能靠二分注释代码定位（本轮排查提现申请 500 时实测踩到）。
     * 出参仍不含内部细节（不泄露堆栈给调用方），但服务端必须留痕。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> onError(Exception e, jakarta.servlet.http.HttpServletRequest req) {
        // 带上方法与路径。这是出 500 时最先被读的一行，只写「未处理异常」
        // 等于只告诉你「有东西坏了」——而日志里同时还有几十条别的请求，
        // 光凭堆栈顶端的类名往往对不上是哪个接口（同一个服务被多个接口复用）。
        log.error("未处理异常 → 500: {} {}", req.getMethod(), req.getRequestURI(), e);
        return Result.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), Messages.msg("error.internal"));
    }
}
