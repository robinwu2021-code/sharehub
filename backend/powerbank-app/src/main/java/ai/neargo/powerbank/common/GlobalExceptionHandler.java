package ai.neargo.powerbank.common;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.Result;
import ai.neargo.common.core.ServerException;
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
@RestControllerAdvice(basePackages = "ai.neargo.powerbank")
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> onError(Exception e) {
        return Result.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), Messages.msg("error.internal"));
    }
}
