package ai.neargo.sharehub.common;

import ai.neargo.common.core.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一把各域 Controller 的返回值包成 neargo {@link Result}（{@code {code:0,message:"success",data:...}}）。
 *
 * <p>复用 neargo-common-core 的 {@code Result} 契约（[TDD-接入层分端与common复用] envelope 方案①）。
 * 仅作用于 {@code ai.neargo.sharehub} 下的 Controller；已是 {@link Result} 的返回值原样透传。
 */
@RestControllerAdvice(basePackages = "ai.neargo.sharehub")
public class ApiResponseWrapper implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result<?>) {
            return body;
        }
        return Result.ok(body);
    }
}
