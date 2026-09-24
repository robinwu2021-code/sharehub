package ai.neargo.sharehub.audit;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.CurrentUser;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.Realm;
import ai.neargo.sharehub.config.AuditTrailInterceptor;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditDetail;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditLogEntry;
import ai.neargo.sharehub.platform.org.service.AuditLogService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 审计写不进去时会怎样（T3-4）。
 *
 * <h2>这里有两个方向相反的要求，都必须成立</h2>
 * <ol>
 *   <li><b>不能掀翻业务</b>：本表是操作留痕不是账务凭证。为了记一条审计
 *       让用户的保存操作 500，是拿真实损失换留痕。</li>
 *   <li><b>不能安静</b>：正因为它会降级，所以降级必须吵。原实现是 {@code log.warn} ——
 *       WARN 在生产日志里淹没得太快，结果是<b>审计可以悄悄停止工作</b>，
 *       而事后翻出来的是一段看起来正常、实际缺行的历史，
 *       没有任何迹象提示它缺了。ERROR 会进 {@code error/} 那份小文件，告警接的就是它。</li>
 * </ol>
 *
 * <p>只有这两条一起断言才有意义：单独测「不抛异常」会被「什么都不做」满足，
 * 而那正是最坏的实现。
 */
class AuditFailureIsAlertedTest {

    private ListAppender<ILoggingEvent> captured;
    private Logger interceptorLog;

    @BeforeEach
    void captureLogs() {
        interceptorLog = (Logger) LoggerFactory.getLogger(AuditTrailInterceptor.class);
        captured = new ListAppender<>();
        captured.start();
        interceptorLog.addAppender(captured);
        CurrentUser.set(new LoginUser(Realm.STAFF, "U1", "张三", "ADMIN", List.of("*"), "MAIN", null, null));
    }

    @AfterEach
    void cleanUp() {
        interceptorLog.detachAppender(captured);
        CurrentUser.clear();
    }

    @Test
    @DisplayName("★★ 审计写失败：业务照常返回，但必须打 ERROR（否则审计能悄悄失效）")
    void a_failing_audit_write_is_loud_but_does_not_break_the_request() {
        var interceptor = new AuditTrailInterceptor(new ExplodingAuditLog());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ops/venues");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);

        assertThatCode(() -> interceptor.afterCompletion(req, res, new Object(), null))
                .as("审计写失败不该让业务请求 500 —— 拿真实损失换留痕是本末倒置")
                .doesNotThrowAnyException();

        assertThat(captured.list)
                .as("""
                        必须留下 ERROR。降级本身是对的，但降级要吵 ——
                        WARN 在生产日志里淹没得太快，而「审计悄悄停止工作」的后果是
                        事后翻出一段看起来正常、实际缺行的历史。""")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getFormattedMessage())
                            .as("消息要能回答「谁该做什么」，而不只是「出错了」")
                            .contains("/api/ops/venues");
                });
    }

    /** 写入必炸的审计实现 —— 「写不进去时会怎样」只能这么测。 */
    private static class ExplodingAuditLog implements AuditLogService {
        @Override
        public PageResult<AuditLogEntry> page(Integer page, Integer size, String keyword,
                                              String actor, String action, String targetType) {
            return new PageResult<>(List.of(), 0L);
        }

        @Override
        public AuditDetail detail(String id) {
            return null;
        }

        @Override
        public void append(Entry entry) {
            throw new IllegalStateException("iam_audit_log 写不进去（模拟：表被锁 / 磁盘满）");
        }
    }
}
