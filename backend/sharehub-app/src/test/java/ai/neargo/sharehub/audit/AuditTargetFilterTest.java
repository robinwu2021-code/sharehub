package ai.neargo.sharehub.audit;

import ai.neargo.sharehub.platform.org.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计的「操作对象」两列存的是<b>业务值</b>，不是 JSON 字符串字面量。
 *
 * <h2>为什么要专门钉住</h2>
 * 这两列曾带 {@code json_valid} CHECK（V13 对账产物），于是拦截器把值 {@code json(...)}
 * 包一层去迁就它，读侧再拆回来。<b>一包一拆之间，中间那层拆不掉</b> ——
 * 按值过滤比的是库里的原样：
 * <pre>
 *   WHERE target_type = 'iam'     → 0 行
 *   WHERE target_type = '"iam"'   → 300 行
 * </pre>
 * V79 去掉了约束、去掉了包裹、把存量的引号剥了。本用例走的正是那条过滤路径。
 *
 * <h2>为什么不走 HTTP</h2>
 * {@code GET /api/platform/audit-logs} 今天只传 {@code keyword}，
 * {@code targetType} 参数在服务端签名里但没接出去 —— 也正因为没接出去，
 * 这个缺陷一直没人踩到。<b>没接出去不等于不会错</b>：接上去的那天返回的是空列表，
 * 而空列表和「确实没有」长得一模一样。所以这里直接对着服务断言。
 */
class AuditTargetFilterTest extends AuditTestSupport {

    @Autowired
    private AuditLogService auditLogs;

    @Test
    @DisplayName("★★ 按操作对象类型过滤审计，要能查到——存成 JSON 字面量时这里永远是空的")
    void filtering_audit_by_target_type_finds_the_row() {
        String admin = login("ADMIN");
        String tp = newTraceparent();
        // 机柜上的写操作 → targetType 落 "cabinets"（targetOf 取路径第 4 段）
        postWithHeaders("/api/ops/cabinets/CAB1005", Map.of("status", "DEPLOYED"),
                admin, "traceparent", tp).okData();

        var page = auditLogs.page(1, 200, null, null, null, "cabinets");

        // **按本次请求的 traceId 找**，不是看「有没有查到东西」——
        // 库是累积的，历史上已有几百行 target_type='cabinets'，
        // 只断言 total>0 的话，写侧改回「包一层 JSON」它照样绿（实测过，就是这么假绿的）。
        boolean mine = page.getList().stream()
                .anyMatch(r -> traceIdOf(tp).equals(r.traceId()));
        assertThat(mine).as("""
                按 targetType='cabinets' 过滤，查不到**刚刚这一条**。
                十有八九是这一列又被存成了 JSON 字符串字面量（"cabinets" 带引号）——
                过滤用的是裸值，永远匹配不上，而返回空列表与「确实没有」无法区分。
                查一眼：SELECT target_type FROM iam_audit_log ORDER BY id DESC LIMIT 5""")
                .isTrue();

        assertThat(page.getList()).allSatisfy(row -> assertThat(row.targetType())
                .as("过滤出来的行，类型必须就是筛的那个（出参也不该带引号）")
                .isEqualTo("cabinets"));
    }
}
