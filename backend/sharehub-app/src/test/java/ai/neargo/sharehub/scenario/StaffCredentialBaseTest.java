package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.job.JobRegistry;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3b · B1 凭据底座。**登录一行不改** —— 这里验的是地基铺对了，不是登录能用了。
 *
 * <h3>为什么卡口不是「每个在职员工都有 email_hash」</h3>
 * 设计文档原话是那样写的，但实测：生产 5 个在职员工全都有邮箱，
 * 而**测试库有 200 个在职、其中 195 个邮箱手机皆空**（种子造的）。
 * 按那句话写，这条用例在测试库上永远红，而红的原因与回填对不对毫无关系 ——
 * 常红的卡口等于没有卡口。
 *
 * <p>所以判据改成「**该补的都补上了，且补对了**」：有明文的行必须有 hash，
 * 且 hash 与 {@link IdentifierHasher} 当场算出来的一致。
 */
class StaffCredentialBaseTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JobRegistry jobs;

    @Autowired
    private IdentifierHasher hasher;

    @Autowired
    private IdentifierNormalizer normalizer;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ——— 表与列 ———

    @Test
    @DisplayName("cred_credential 建出来了，且一个主体在一个 realm 下只有一份凭据")
    void credential_table_exists_with_unique_subject() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cred_credential'",
                Integer.class)).isEqualTo(1);

        /*
         * 没有这条唯一约束的话，同一个人可能挂着两份口令：
         * 改了其中一份，而用另一份仍然能登进来 —— 而两边都不报错。
         */
        List<Map<String, Object>> idx = jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'cred_credential' "
                        + "AND index_name = 'uk_realm_subject' AND non_unique = 0");
        assertThat(idx).as("(realm, subject_no) 必须唯一").isNotEmpty();
    }

    @Test
    @DisplayName("★ 身份列可空——存量库里大量员工没有邮箱/手机，建成 NOT NULL 这条迁移会直接失败")
    void identity_columns_are_nullable() {
        for (String col : List.of("email_hash", "phone_hash")) {
            assertThat(jdbc.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = 'iam_employee' AND column_name = ?",
                    String.class, col)).as(col).isEqualTo("YES");
        }
    }

    @Test
    @DisplayName("★ 不在可空列上建唯一索引——MySQL 里它对 NULL 不生效，会看起来建成了而实际什么都没拦")
    void no_unique_index_on_nullable_hash_columns() {
        /*
         * V49 的 uk_scope_target 就是这么「什么都没拦住」的：可空列上的唯一索引，
         * NULL 之间互不相同，于是两行都落了库。这里 195 个 NULL 行会让同样的事再发生一次。
         */
        assertThat(jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'iam_employee' "
                        + "AND column_name IN ('email_hash','phone_hash') AND non_unique = 0"))
                .as("唯一性由写入侧保证，不靠可空列上的索引").isEmpty();
    }

    // ——— 口令编码器 ———

    @Test
    @DisplayName("★ PasswordEncoder 是 BCrypt cost 10——cost 是有意的成本，不是可调的性能参数")
    void password_encoder_is_bcrypt_cost_10() {
        String hash = passwordEncoder.encode("s3cret-example");
        // BCrypt 串自带 cost：$2a$10$...
        assertThat(hash).as("cost 被调低的话这里会变成 $2a$04$ 之类").startsWith("$2a$10$");
        assertThat(passwordEncoder.matches("s3cret-example", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong", hash)).isFalse();
    }

    @Test
    @DisplayName("同一口令两次编码结果不同（盐），但都能校验通过")
    void encoding_is_salted() {
        String a = passwordEncoder.encode("same");
        String b = passwordEncoder.encode("same");
        assertThat(a).as("两次一样说明没加盐，彩虹表就能用").isNotEqualTo(b);
        assertThat(passwordEncoder.matches("same", a)).isTrue();
        assertThat(passwordEncoder.matches("same", b)).isTrue();
    }

    // ——— 回填任务 ———

    @Test
    @DisplayName("回填任务已登记（藏在启动逻辑里的话，运维不知道它存在、跑没跑过）")
    void backfill_job_is_registered() {
        assertThat(jobs.has("staff-identity-backfill")).isTrue();
    }

    @Test
    @DisplayName("★★ 有明文的员工必须被补上 hash，且 hash 与当场算出来的一致")
    @Transactional   // 回填会真写库，测完回滚
    void backfill_fills_and_is_correct() {
        JobResult r = jobs.trigger("staff-identity-backfill");
        assertThat(r.status()).as(r.error()).isIn(JobResult.Status.SUCCESS, JobResult.Status.SKIPPED);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT employee_no, email, phone, email_hash, phone_hash FROM iam_employee "
                        + "WHERE (email IS NOT NULL AND email <> '') OR (phone IS NOT NULL AND phone <> '')");
        assertThat(rows).as("测试库里至少有几个带邮箱的员工，否则这条用例什么都没验").isNotEmpty();

        for (Map<String, Object> row : rows) {
            String email = (String) row.get("email");
            if (email != null && !email.isBlank()) {
                assertThat((String) row.get("email_hash"))
                        .as("员工 %s 有邮箱却没 hash", row.get("employee_no"))
                        .isEqualTo(hasher.hash(IdentifierNormalizer.email(email)));
            }
        }
    }

    @Test
    @DisplayName("★ 幂等：第二次跑不该再改任何行（否则它在反复重算，pepper 轮换与补齐就混成一件事了）")
    @Transactional
    void backfill_is_idempotent() {
        jobs.trigger("staff-identity-backfill");
        JobResult second = jobs.trigger("staff-identity-backfill");
        assertThat(second.status())
                .as("第二次应为 SKIPPED（没有待回填的了）：%s", second.detail())
                .isEqualTo(JobResult.Status.SKIPPED);
    }

    @Test
    @DisplayName("★ 没有邮箱也没有手机的员工不会被硬塞一个 hash——那等于给他造了一个登不进去的身份")
    @Transactional
    void employees_without_identity_are_left_alone() {
        jobs.trigger("staff-identity-backfill");
        Integer bogus = jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_employee "
                        + "WHERE (email IS NULL OR email = '') AND email_hash IS NOT NULL",
                Integer.class);
        assertThat(bogus).isZero();
    }

    @Test
    @DisplayName("登录一行没改——B1 是地基，共享口令闸照常工作")
    void login_is_untouched() {
        // 能登进来就说明既有登录路径没被这一批破坏
        assertThat(login("ADMIN")).isNotBlank();
    }
}
