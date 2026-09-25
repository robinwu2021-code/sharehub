package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.platform.md.entity.MdProblem;
import ai.neargo.sharehub.platform.md.mapper.MdProblemMapper;
import ai.neargo.sharehub.platform.md.service.ProblemService;
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
    /** 只用于「建单」用例 —— 与 KEY 分开，免得 seed() 的存在把创建路径变成更新路径。 */
    private static final String NEW_KEY = "zztest.mass.assignment.create";

    @Autowired
    SysParamService params;

    @Autowired
    SysParamMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProblemService problems;

    @Autowired
    MdProblemMapper problemMapper;

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
        jdbc.update("DELETE FROM md_problem WHERE problem_no = ?", P_KEY);
        jdbc.update("DELETE FROM sys_param WHERE param_key = ?", NEW_KEY);
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

    // ——————————————————— 归档时间戳 ———————————————————
    //
    // 上面四条锁的是 tenantId / deleted / createdAt / id+version，**独独漏了 archivedAt**。
    // 基类注释里那句「归档走 archive/unarchive」说的是 deleted，而 archivedAt 才是
    // 运营端真正看得见的归档位（Archivable 的契约：null = 在用，非空 = 已归档）。
    //
    // 16 个 Archivable 实体里只有 LocService 与 NoticeServiceImpl 在自己的 beforeUpdate
    // 里补了一句 setArchivedAt(current.getArchivedAt())，其余 14 个没有 ——
    // **那是黑名单：得有人记得写**。这里把它挪到基类，改成白名单。
    //
    // 可利用的方向只有一个：updateById 是 NOT_NULL 策略，传 null 不会进 UPDATE SET，
    // 所以经 save **取消不了**归档；能做的是反向 —— 把任意记录设成已归档、并伪造归档时间。
    // 归档的记录从默认列表消失，于是「运营点了保存，这条就不见了」，且没有任何报错。

    private static final String P_KEY = "zztest-archive-guard";

    private MdProblem seedProblem() {
        MdProblem e = new MdProblem();
        e.setProblemNo(P_KEY);
        e.setCategory("OTHER");
        e.setTitle("归档加固探针");
        problems.save(e);
        return reloadProblem();
    }

    private MdProblem reloadProblem() {
        return problemMapper.selectOne(Wrappers.<MdProblem>lambdaQuery()
                .eq(MdProblem::getProblemNo, P_KEY).last("limit 1"));
    }

    @Test
    @DisplayName("★★ 更新不能顺手把记录归档掉——归档有专门入口")
    void archivedAtCannotBeSetThroughSave() {
        MdProblem before = seedProblem();
        assertThat(before).as("前置条件：探针记录建起来了").isNotNull();
        assertThat(before.getArchivedAt()).as("前置条件：刚建的未归档").isNull();

        MdProblem evil = new MdProblem();
        evil.setProblemNo(P_KEY);
        evil.setTitle("改个标题而已");
        evil.setArchivedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        problems.save(evil);

        MdProblem after = reloadProblem();
        assertThat(after.getArchivedAt())
                .as("归档位只认 archive 入口，保存端点传什么都不算")
                .isNull();
        assertThat(after.getTitle())
                .as("正当字段照常写入 —— 只验「改不了」会把功能堵死还以为修好了")
                .isEqualTo("改个标题而已");
    }

    @Test
    @DisplayName("加固之后 archive / unarchive 本身仍要好使")
    void archiveEndpointStillWorks() {
        seedProblem();

        problems.archive(P_KEY);
        assertThat(reloadProblem().getArchivedAt()).as("专用入口该归档得上").isNotNull();

        problems.unarchive(P_KEY);
        assertThat(reloadProblem().getArchivedAt()).as("也该取消得掉").isNull();
    }

    // ——————————————————— 创建路径 ———————————————————
    //
    // 上面所有用例走的都是**更新**分支。2026-09-23 的加固也只改了那一支：
    //
    //   建单（自然键）： if (body.getTenantId() == null) body.setTenantId(TENANT_MAIN);
    //   更新：           body.setTenantId(current.getTenantId());   // 越租户搬数据
    //
    // 上面那一行，正是加固注释里点名说「此前写成『客户端没传才取库里的』，于是传了就能
    // 把这行数据搬到别的租户」的**同一个形状** —— 它在创建分支上原样留着。
    // 建单时传 tenantId，这条记录就直接建到别人的租户里。
    //
    // 同一支上还有两个：
    //   · deleted=1 —— 行插进去了却查不到（@TableLogic 过滤），可唯一键被占着，
    //     于是「保存成功 → 列表里没有 → 再建一次报重复」，运营端看不出任何原因；
    //   · createdBy / createdAt —— insert 侧是 strictInsertFill（**只填空值**，
    //     见 AuditMetaObjectHandler 末段：那是给迁移脚本留的口子），
    //     所以客户端传了就算数，创建人可以署成别人。
    //
    // 读库一律走 jdbc 而不是 mapper：deleted 带 @TableLogic，用 mapper 查「有没有被软删」
    // 会因为查不到而误判成「没建成」——那是把夹具错误读成结论。

    private java.util.Map<String, Object> rawNew() {
        var rows = jdbc.queryForList("SELECT * FROM sys_param WHERE param_key = ?", NEW_KEY);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 一次「带恶意字段的建单」：只有 value 是正当的。 */
    private void createWithMaliciousFields() {
        SysParam evil = new SysParam();
        evil.setParamKey(NEW_KEY);
        evil.setValue("新建的值");                                  // 正当
        evil.setTenantId(OTHER_TENANT);                            // 建到别人的租户
        evil.setDeleted(1);                                        // 建出一条查不到却占着键的行
        evil.setCreatedBy("EMP_SOMEONE_ELSE");                     // 伪造创建人
        evil.setCreatedAt(LocalDateTime.of(2000, 1, 1, 0, 0));     // 伪造创建时间
        params.save(evil);
    }

    @Test
    @DisplayName("★★ 建单不能指定租户——加固只改了更新分支，创建分支同一行还在")
    void tenantCannotBeChosenOnCreate() {
        assertThat(rawNew()).as("前置条件：这个键还不存在，走的确实是创建分支").isNull();

        createWithMaliciousFields();

        var row = rawNew();
        assertThat(row).as("前置条件：记录得真的建出来了").isNotNull();
        assertThat(row.get("tenant_id"))
                .as("建单的隔离键由服务端定，传什么都不算")
                .isNotEqualTo(OTHER_TENANT);
    }

    @Test
    @DisplayName("★★ 建单不能直接建成已软删的——那会占着唯一键却查不到")
    void deletedCannotBeSetOnCreate() {
        createWithMaliciousFields();

        var row = rawNew();
        assertThat(row).isNotNull();
        assertThat(String.valueOf(row.get("deleted")))
                .as("建出来就该是可见的 —— 否则运营会看到「保存成功但列表里没有，再建报重复」")
                .isEqualTo("0");
    }

    @Test
    @DisplayName("★★ 建单不能伪造创建人")
    void createdByCannotBeForgedOnCreate() {
        createWithMaliciousFields();

        var row = rawNew();
        assertThat(row).isNotNull();
        assertThat(row.get("created_by"))
                .as("insert 侧是「只填空值」的 strict 策略，所以必须在 service 里先清掉客户端传的")
                .isNotEqualTo("EMP_SOMEONE_ELSE");
        assertThat(row.get("created_by")).as("清掉之后要由填充器补上，不能留 NULL").isNotNull();
    }

    @Test
    @DisplayName("创建路径的加固同样不能把正常建单堵死")
    void legitimateCreateStillWorks() {
        createWithMaliciousFields();

        var row = rawNew();
        assertThat(row).isNotNull();
        assertThat(row.get("value")).as("正当字段必须照常写入").isEqualTo("新建的值");
    }
}
