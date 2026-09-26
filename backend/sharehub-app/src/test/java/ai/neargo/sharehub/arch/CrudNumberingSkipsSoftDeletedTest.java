package ai.neargo.sharehub.arch;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取号必须把**软删的行**算进去 —— 覆盖所有走 {@link AbstractCrudService} 的实体。
 *
 * <h2>这个缺陷长什么样</h2>
 * 2026-09-26 生产实测：代理商审核通过时 {@code Duplicate entry 'AG010' for key 'uk_agent_no'}，
 * 而运营台列表里**完全看不到 AG010**。两件事撞在一起：
 *
 * <ul>
 *   <li>业务键的唯一索引**不含 {@code deleted} 列** —— 这是对的：软删/归档掉的编号被合同、
 *       结算、划拨记录引用着，不能被重新发出去；</li>
 *   <li>而取号走 MyBatis-Plus 的 {@code QueryWrapper} 取 MAX 时，{@code @TableLogic}
 *       会自动补上 {@code deleted = 0} —— **取号看不见那些行**。</li>
 * </ul>
 *
 * 于是只要有一行被归档，它的号就是一个洞，下一次取号必然走进去。
 * 而 {@link AbstractCrudService#remove} 正是软删 —— **删掉一个员工，下次新建员工就 500**。
 *
 * <p>症状极具欺骗性：报 500，列表却一切正常（那行被逻辑删除藏起来了），
 * 第一反应会往业务逻辑、事务、权限上找，而根因在取号。
 *
 * <h2>为什么要逐个 bean 验，而不是只看一眼代码</h2>
 * 修法是让 {@code nextNo} 走原生 SQL，表名由 {@code tableName()} 反射泛型取得。
 * **反射能不能拿到泛型，取决于运行时的类**：Spring 默认用 CGLIB 代理（{@code @Transactional}
 * 等切面），此时 {@code getClass()} 是代理子类；子类若不直接继承本类（中间还有一层抽象），
 * 泛型也会丢。这两种情况 grep 查不出来，只有把容器里真实的 bean 逐个问一遍才算数。
 *
 * <p>所以这条用例做两件事：①每个 bean 的表名都解析得出来；
 * ②取号真的看得见软删的行（用一张真表端到端验一次）。
 */
@SpringBootTest
@DisplayName("取号跨过软删的号（全部 CRUD 实体）")
class CrudNumberingSkipsSoftDeletedTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("★ 每个 CRUD 服务都解析得出表名 —— 反射拿泛型在代理下会失效，只能逐个问")
    void every_crud_service_resolves_its_table_name() throws Exception {
        Map<String, AbstractCrudService> beans =
                new TreeMap<>(ctx.getBeansOfType(AbstractCrudService.class));
        assertThat(beans).as("一个都没扫到，说明这条用例本身失效了").isNotEmpty();

        Method m = AbstractCrudService.class.getDeclaredMethod("tableName");
        m.setAccessible(true);

        List<String> broken = new ArrayList<>();
        Map<String, String> resolved = new TreeMap<>();
        for (Map.Entry<String, AbstractCrudService> e : beans.entrySet()) {
            try {
                String t = String.valueOf(m.invoke(e.getValue()));
                resolved.put(e.getKey(), t);
                // 表名解析出来还不够 —— 得确认这张表真的存在，否则取号时才会炸
                jdbc.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE 1=0", Integer.class);
            } catch (Exception ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                broken.add(e.getKey() + " → " + cause.getMessage());
            }
        }
        assertThat(broken)
                .as("这些 CRUD 服务取号时会炸（覆盖 tableName() 显式给表名即可）：\n  %s",
                        String.join("\n  ", broken))
                .isEmpty();
        assertThat(resolved).as("解析到的表名").isNotEmpty();
    }

    @Test
    @DisplayName("★ 取号看得见软删的行 —— 端到端验一张真表")
    void numbering_sees_soft_deleted_rows() {
        /*
         * 用部门表（dept_no 前缀取号，且有 deleted 列）。
         * 造一个「软删掉的下一个号」，再取一次号，断言跳过了它。
         * 直接调 BizNoSequence 而不是走 save() —— 这里要钉的是取号本身，
         * 走完整建档会把断言的失败面扩大到建档链路上的任何一处。
         */
        Long max = jdbc.queryForObject(
                "SELECT MAX(CAST(SUBSTRING(dept_no, 2) AS UNSIGNED)) FROM iam_dept WHERE dept_no LIKE 'D%'",
                Long.class);
        String hole = "D" + String.format("%04d", (max == null ? 0 : max) + 1);
        jdbc.update("INSERT INTO iam_dept (dept_no, tenant_id, name, deleted) VALUES (?, 'MAIN', ?, 1)",
                hole, "【测试】软删占位");
        try {
            String next = ai.neargo.sharehub.common.crud.BizNoSequence
                    .next(jdbc, "iam_dept", "dept_no", "D", 4);
            assertThat(next).as("取号撞上了被软删占着的 %s —— 那个号仍占着唯一索引", hole)
                    .isNotEqualTo(hole);
        } finally {
            jdbc.update("DELETE FROM iam_dept WHERE dept_no=?", hole);
        }
    }
}
