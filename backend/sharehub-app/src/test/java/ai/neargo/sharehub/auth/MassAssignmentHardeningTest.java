package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.platform.sys.entity.SysParam;
import ai.neargo.sharehub.platform.sys.mapper.SysParamMapper;
import ai.neargo.sharehub.platform.sys.service.SysParamService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量赋值加固（TDD-mass-assignment-hardening T5）。
 *
 * <p>直接打 {@code AbstractCrudService.save} —— 漏洞与修复都在那一处，走 HTTP 只会把断言埋在
 * 信封与权限之后。加固写在基类里，对 33 个继承者是同一份代码。
 *
 * <h3>为什么用 {@code SysParam} 而不是更简单的字典表</h3>
 * 第一版用了 {@code MdBank}，三条断言**全部恒真** —— `md_` 前缀是全局表，**根本没有
 * `tenant_id` 列**，于是「租户没被改」这件事不证自明。反向对照（把加固还原成有漏洞的写法）
 * 照样全绿，才暴露了这一点。`sys_param` 带 `tenant_id` + `deleted` + `version`，断言才有意义。
 *
 * <p>所以每条断言前都先验**前置条件**（基准值非 null）：字段若不存在或恒空，测试要**响**，
 * 而不是默默通过。一个永远绿的测试比没有测试更糟 —— 它会让人以为这里有防护。
 *
 * <p>另外**两个方向都验**：既验「改不了」，也验「还能改」——
 * 只验前者的话，把接口改成谁都写不进去也会通过。
 */
@SpringBootTest(properties = "sharehub.dev-mode.enabled=false")
class MassAssignmentHardeningTest {

    private static final String KEY = "zztest.mass.assignment";
    private static final String OTHER_TENANT = "EVIL-TENANT";

    @Autowired
    SysParamService params;

    @Autowired
    SysParamMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * **物理**删除，不能用 {@code mapper.delete}。
     *
     * <p>{@code deleted} 带 {@code @TableLogic}，mapper 的 delete 是**软删** —— 行还在，
     * 唯一键 {@code (tenant_id, param_key)} 仍被占着，下一次 insert 直接撞键。
     * 这正是本仓库「全站零 DELETE」语义的体现：业务删不掉数据，清夹具就得绕过 ORM。
     */
    @BeforeEach
    @AfterEach
    void hardDelete() {
        jdbc.update("DELETE FROM sys_param WHERE param_key = ?", KEY);
    }

    private SysParam seed() {
        SysParam e = new SysParam();
        e.setParamKey(KEY);
        e.setValue("原值");
        params.save(e);
        SysParam saved = reload();
        assertThat(saved).as("夹具必须真的写进去了").isNotNull();
        return saved;
    }

    private SysParam reload() {
        return mapper.selectOne(Wrappers.<SysParam>lambdaQuery().eq(SysParam::getParamKey, KEY));
    }

    /** 一次「带恶意字段的更新」：只改值是正当的，其余都是越权尝试。 */
    private void updateWithMaliciousFields() {
        SysParam evil = new SysParam();
        evil.setParamKey(KEY);
        evil.setValue("改过的值");        // 正当修改
        evil.setTenantId(OTHER_TENANT);        // 越租户搬数据
        evil.setDeleted(1);                    // 绕过归档语义软删
        evil.setCreatedAt(LocalDateTime.of(2000, 1, 1, 0, 0));   // 伪造审计
        params.save(evil);
    }

    @Test
    @DisplayName("更新不能把数据搬到别的租户")
    void tenantCannotBeChanged() {
        SysParam before = seed();
        assertThat(before.getTenantId())
                .as("前置条件：本表必须真有 tenant_id，否则这条断言恒真、测不出东西")
                .isNotNull()
                .isNotEqualTo(OTHER_TENANT);

        updateWithMaliciousFields();

        assertThat(reload().getTenantId())
                .as("租户由服务端决定，客户端传了也不算数")
                .isEqualTo(before.getTenantId());
    }

    @Test
    @DisplayName("更新不能设置软删标记（归档只能走 archive）")
    void deletedFlagCannotBeSet() {
        SysParam before = seed();
        assertThat(before.getDeleted()).as("前置条件：本表必须真有 deleted 列").isNotNull();

        updateWithMaliciousFields();

        SysParam after = reload();
        assertThat(after).as("记录必须还在 —— deleted=1 不该生效").isNotNull();
        assertThat(after.getDeleted()).isEqualTo(before.getDeleted());
    }

    @Test
    @DisplayName("更新不能伪造创建时间")
    void createdAtCannotBeForged() {
        SysParam before = seed();
        assertThat(before.getCreatedAt()).as("前置条件：本表必须真有 created_at").isNotNull();

        updateWithMaliciousFields();

        assertThat(reload().getCreatedAt()).isEqualTo(before.getCreatedAt());
    }

    @Test
    @DisplayName("加固不能把正常修改也堵死")
    void legitimateFieldsStillWritable() {
        seed();
        updateWithMaliciousFields();
        assertThat(reload().getValue())
                .as("正当字段必须照常写入 —— 只验「改不了」会把功能堵死还以为修好了")
                .isEqualTo("改过的值");
    }

    @Test
    @DisplayName("id 与乐观锁版本仍由服务端决定")
    void idAndVersionStillServerControlled() {
        SysParam before = seed();

        SysParam evil = new SysParam();
        evil.setParamKey(KEY);
        evil.setValue("再改一次");
        evil.setId(999_999_999L);
        evil.setVersion(4242L);
        params.save(evil);

        SysParam after = reload();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getVersion()).as("版本由乐观锁推进，不由客户端指定").isNotEqualTo(4242L);
    }
}
