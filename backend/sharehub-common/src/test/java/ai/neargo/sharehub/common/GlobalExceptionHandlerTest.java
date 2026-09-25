package ai.neargo.sharehub.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务码 → HTTP 状态的推导。
 *
 * <p>钉的是一条边界：**只有能解析成 4xx/5xx 的码才改状态**。
 * 无脑 {@code HttpStatus.resolve} 会把业务自定义码域（比如 10001）当成状态码，
 * 而 {@code Result} 明确允许自定义码 —— 那样一来一个业务码就能让响应变成
 * 一个谁也没打算返回的 HTTP 状态。
 */
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("409/404 这类真 HTTP 码 → 原样用作状态码")
    void http_like_codes_become_the_status() {
        assertThat(GlobalExceptionHandler.statusOf(409)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(GlobalExceptionHandler.statusOf(404)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(GlobalExceptionHandler.statusOf(500)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("★ 业务自定义码 → 回 200，不拿别人的码域当状态码")
    void business_codes_stay_on_200() {
        assertThat(GlobalExceptionHandler.statusOf(10001)).isEqualTo(HttpStatus.OK);
        assertThat(GlobalExceptionHandler.statusOf(0)).isEqualTo(HttpStatus.OK);
        assertThat(GlobalExceptionHandler.statusOf(-1)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("2xx/3xx 也不改状态——「成功」和「重定向」都不是业务拒绝该落的地方")
    void non_error_codes_stay_on_200() {
        assertThat(GlobalExceptionHandler.statusOf(204)).isEqualTo(HttpStatus.OK);
        assertThat(GlobalExceptionHandler.statusOf(302)).isEqualTo(HttpStatus.OK);
    }
}
