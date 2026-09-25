package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 押金处置的三个动作，各归各的码 —— **解冻/买断是动钱，催缴只留痕**。
 *
 * <h2>真源表把话说得很清楚，实现没跟上</h2>
 * 功能权限清单 §4（2026-07-30 定）：
 * <pre>
 *   押金 解冻/买断 | order:deposit:manage | 仅 FIN
 *   欠费 催缴      | order:arrears:dun    | CS · FIN
 * </pre>
 * 并写明理由：「解冻/买断是动钱（放弃或没收用户押金），归财务；催缴只留痕不改钱，
 * 是客服日常动作。**合成一个 order:order:update 会让客服为了催缴而拿到买断权**。」
 *
 * <h2>而三个端点实际用的是另外两个码</h2>
 * <pre>
 *   /deposits/{no}/buyout  → order:deposit:update      ← 真源表里根本没有这个码
 *   /deposits/{no}/dun     → order:deposit:update      ← 同上，且与买断同码
 *   /deposits/{no}/release → order:intervene:execute   ← 客服持有
 * </pre>
 *
 * 后果有三层，一层比一层糟：
 * <ol>
 *   <li>运营端按 {@code order:deposit:manage} / {@code order:arrears:dun} 渲染按钮，
 *       而这两个码<b>没有任何端点检查</b> —— 持有它们的人按钮全亮、点下去全 403；</li>
 *   <li>反过来，持 {@code order:deposit:update} 的人界面上什么都看不到，却能调通接口；</li>
 *   <li><b>买断与催缴同码</b>，正好是真源表那条注释要防的事 ——
 *       给客服催缴权就等于给了买断权。</li>
 * </ol>
 *
 * <p>所以这里改的是<b>后端</b>：前端与真源表一致，是实现漂了。
 */
class DepositPermissionTest extends ApiTestSupport {

    /** 只做鉴权判定，不关心业务结果：403 与「非 403」是本测试唯一在意的分界。 */
    private int status(String path, String token) {
        return post(path, Map.of("reason", "权限探针"), token).status;
    }

    @Test
    @DisplayName("★★ 催缴给客服，买断不给——合成一个码等于给客服买断权")
    void dunning_is_for_cs_but_buyout_is_not() {
        String cs = login("CS");

        // 催缴：客服的日常动作，不该 403（单号不存在会是别的错，但不能是 403）
        assertThat(status("/api/trade/deposits/DEP-NOPE/dun", cs))
                .as("催缴只留痕不改钱，是客服日常 —— 不该被鉴权拦住")
                .isNotEqualTo(403);

        // 买断：动的是用户的钱，只归财务
        assertThat(status("/api/trade/deposits/DEP-NOPE/buyout", cs))
                .as("买断是没收用户押金，客服不该有 —— 这正是真源表分两个码要防的事")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("★★ 解冻也是动钱，同样不给客服")
    void releasing_is_not_for_cs_either() {
        String cs = login("CS");
        assertThat(status("/api/trade/deposits/DEP-NOPE/release", cs))
                .as("解冻是放弃押金，与买断同级，归财务")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("财务三个动作都能做")
    void finance_can_do_all_three() {
        String fin = login("FINANCE");
        for (String p : new String[]{"dun", "buyout", "release"}) {
            assertThat(status("/api/trade/deposits/DEP-NOPE/" + p, fin))
                    .as("财务对押金三个动作都有权（%s）", p)
                    .isNotEqualTo(403);
        }
    }
}
