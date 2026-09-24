package ai.neargo.sharehub.audit;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计相关用例的公共脚手架。
 *
 * <h2>为什么不能用「该操作人最新的一条」</h2>
 * 这是本文件存在的唯一理由。测试库是<b>累积</b>的（见 application.properties），
 * 「查这个人最新的一条审计」在上一次跑留下同样形状的记录时会读到那一条 ——
 * 于是「本次操作压根没被记下来」这个缺陷，会被<b>上一次的成功记录掩盖</b>。
 *
 * <p>实测踩到过：把拦截器改回「{@code >=400} 就跳过」之后，
 * 断言 403 要留痕的那条用例<b>照样绿</b>，因为它读到的是上一轮留下的 DENIED 行。
 *
 * <p>所以每次请求自带一个唯一的 {@code traceparent}，再按 traceId 回找那一行：
 * 找不到就是<b>真的没记</b>。这同时也顺便证明了 traceId 这根线是通的。
 */
abstract class AuditTestSupport extends ApiTestSupport {

    /** 一个唯一的合法 W3C traceparent（{@code 00-<32hex>-<16hex>-01}）。 */
    protected static String newTraceparent() {
        return "00-" + hex(16) + "-" + hex(8) + "-01";
    }

    protected static String traceIdOf(String traceparent) {
        return traceparent.substring(3, 35);
    }

    /**
     * 找出**本次请求**留下的那条审计。找不到 = 这次操作没有留痕，直接失败。
     *
     * @param actor   操作人，用来把查询范围缩到本人（避免并行用例互相干扰）
     * @param traceId 本次请求的链路 id —— 精确定位靠它，不靠「最新一条」
     */
    protected JsonNode auditOfThisRequest(String actor, String traceId) {
        JsonNode row = findInPages("/api/platform/audit-logs?keyword=" + actor, "traceId", traceId, login("ADMIN"));
        if (row == null) {
            throw new AssertionError("本次操作没有在审计里留痕：actor=" + actor + " traceId=" + traceId);
        }
        return row;
    }

    /** 审计查询要 org:audit:read —— 代理没有这个码，所以固定用 ADMIN 读。 */
    protected void assertNoAuditFor(String actor, String traceId) {
        assertThat(findInPages("/api/platform/audit-logs?keyword=" + actor, "traceId", traceId, login("ADMIN")))
                .as("这次请求不该留痕，却查到了一条").isNull();
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
