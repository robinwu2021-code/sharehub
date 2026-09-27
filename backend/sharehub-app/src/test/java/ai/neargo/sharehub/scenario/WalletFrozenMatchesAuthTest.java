package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钱包页的「免押冻结」必须等于这个人名下还冻着的预授权之和。
 *
 * <h3>2026-09-27 H5 对接实测</h3>
 * 免押借出会建一条 {@code pay_auth status=FROZEN freeze_amount=50}，
 * 而钱包页显示「免押冻结 AED 0.00」—— 因为它读的是 {@code usr_wallet.frozen_amount}，
 * 而那一列**全仓没有任何一处写非零值**（建钱包时置 0，之后没人动过）。
 *
 * <p>同一笔钱两个来源、一个恒为 0、没有任何东西对账。修法不是补上第二个写入方
 * （冻结/扣款/释放三处都得记得同步，漏一处就开始漂，而漂了之后两边都「有值」，
 * 比恒 0 更难发现），而是**直接按 pay_auth 求和** —— 真源只有一个，漂不了。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletFrozenMatchesAuthTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("免押预授权之后，钱包的冻结额跟着变（此前恒 0）")
    void frozenReflectsPreAuth() {
        String cabinetNo = jdbc.queryForObject(
                "select cabinet_no from dev_cabinet where deleted=0 and available_count>0 "
                        + "and online_status='ONLINE' and status<>'FAULT' limit 1", String.class);

        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        assertThat(get("/mp/user/wallet", token).okData().path("frozenAmount").asDouble())
                .as("还没冻过，应该是 0").isZero();

        double frozen = post("/mp/trade/deposit/free", Map.of("cabinetNo", cabinetNo), token)
                .okData().path("frozen").asDouble();
        assertThat(frozen).as("预授权本身要真的冻了一笔").isPositive();

        assertThat(get("/mp/user/wallet", token).okData().path("frozenAmount").asDouble())
                .as("""
                        钱包页的「免押冻结」此前恒为 0 —— 它读的那一列没有任何写入方，
                        而 pay_auth 里明明躺着一条 FROZEN。用户看到的是「没冻钱」，
                        实际额度已经被占住了。""")
                .isEqualTo(frozen);
    }

    /**
     * 上面那条用的是刚注册的用户，**他还没有 usr_wallet 行** —— 这一点是有意的。
     *
     * <p>免押预授权不需要先开钱包，所以「没有钱包行」与「没冻钱」是两回事。
     * {@code overviewOf} 的早返回分支此前把两者混为一谈，一并返回全零；
     * 本测试第一次跑时红的就是这里，而不是主分支。
     */
    @Test
    @DisplayName("刚注册（还没有钱包行）也要照样报出冻结额")
    void frozenShowsEvenWithoutWalletRow() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();
        String cUserNo = get("/mp/user/profile", token).okData().path("cUserNo").asText();

        assertThat(jdbc.queryForObject("select count(*) from usr_wallet where c_user_no=?",
                Integer.class, cUserNo)).as("前提：这个用户确实还没有钱包行").isZero();

        String cabinetNo = jdbc.queryForObject(
                "select cabinet_no from dev_cabinet where deleted=0 and available_count>0 "
                        + "and online_status='ONLINE' and status<>'FAULT' limit 1", String.class);
        post("/mp/trade/deposit/free", Map.of("cabinetNo", cabinetNo), token).okData();

        assertThat(get("/mp/user/wallet", token).okData().path("frozenAmount").asDouble())
                .as("没有钱包行 ≠ 没冻钱").isEqualTo(50.0);
    }
}
