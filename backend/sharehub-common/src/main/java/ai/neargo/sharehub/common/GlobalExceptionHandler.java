package ai.neargo.sharehub.common;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.Result;
import ai.neargo.common.core.ServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常 → neargo {@link Result} 错误包（复用 common-core 的 {@link ErrorCode}/{@link ServerException}）。
 *
 * <p>保留 powerbank 特定映射：{@link IllegalArgumentException}→400、{@link AccessDeniedException}→403
 * （neargo {@code ServerExceptionHandler} 未覆盖此二者，会落 500，故本处自管以维持 RBAC/状态机语义）。
 */
@RestControllerAdvice(basePackages = "ai.neargo.sharehub")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ServerException.class)
    public Result<Void> onServer(ServerException e) {
        // message 约定为 i18n key（非 key 的成品串经 Messages 原样返回，兼容旧代码）
        return Result.error(e.getCode(), Messages.msg(e.getMessage()));
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
    public Result<Void> onError(Exception e) {
        log.error("未处理异常 → 500", e);
        return Result.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), Messages.msg("error.internal"));
    }
}
