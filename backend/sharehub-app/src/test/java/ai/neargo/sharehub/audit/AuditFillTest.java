package ai.neargo.sharehub.audit;

import ai.neargo.sharehub.common.AuditMetaObjectHandler;
import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计字段自动填充。
 *
 * <h2>为什么值得单独测</h2>
 * 审计字段错了**不会有任何症状** —— 页面照常保存、接口照常返回 200，
 * 只有在事后查「这行是谁改的」时才会发现答案一直是错的，而那时已经查不回来了。
 *
 * <p>本文件盯的就是这一类：{@code updatedBy} 停在第一次写入时的那个人，
 * 之后**无论谁改都不变**。
 */
class AuditFillTest {

    /** 测试专用实体 —— 不挂业务实体，免得它们改字段时连带这里一起红。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("t_audit_probe")
    static class Probe extends BaseEntity {
        private String name;
    }

    @BeforeAll
    static void registerTableInfo() {
        // strictUpdateFill 要查 TableInfo（没注册会 NPE），纯单测里手动注册一次
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Probe.class);
    }

    private static MetaObject metaOf(Object o) {
        return SystemMetaObject.forObject(o);
    }

    @Test
    @DisplayName("★★★ 更新时 updatedBy 必须被改写——它停在第一次写入的那个人，是查不回来的错")
    void updatedByIsOverwrittenOnUpdate() {
        AuditMetaObjectHandler h = new AuditMetaObjectHandler();
        Probe e = new Probe();
        /*
         * 这正是真实的更新路径：查出实体 → 改字段 → updateById。
         * 查出来的 updatedBy 一定有值 —— 于是「只在字段为 null 时填」的策略永远跳过它。
         */
        e.setUpdatedBy("E1001");
        e.setUpdatedAt(LocalDateTime.now().minusDays(7));

        h.updateFill(metaOf(e));

        assertThat(e.getUpdatedBy())
                .as("无登录态的写入应记 SYSTEM，而不是保留上一个人")
                .isEqualTo(AuditMetaObjectHandler.SYSTEM);
        assertThat(e.getUpdatedAt())
                .as("更新时间也要跟着走")
                .isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    @DisplayName("★★ 字段为空时照样填——别把修复写成「只覆盖非空」的反向错误")
    void updatedByIsFilledWhenBlank() {
        AuditMetaObjectHandler h = new AuditMetaObjectHandler();
        Probe e = new Probe();

        h.updateFill(metaOf(e));

        assertThat(e.getUpdatedBy()).isEqualTo(AuditMetaObjectHandler.SYSTEM);
        assertThat(e.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★ 插入时四个字段都填；createdBy 不因为已有值就被覆盖")
    void insertFillsAllFour() {
        AuditMetaObjectHandler h = new AuditMetaObjectHandler();
        Probe e = new Probe();

        h.insertFill(metaOf(e));

        assertThat(e.getCreatedAt()).isNotNull();
        assertThat(e.getCreatedBy()).isEqualTo(AuditMetaObjectHandler.SYSTEM);
        assertThat(e.getUpdatedAt()).isNotNull();
        assertThat(e.getUpdatedBy()).isEqualTo(AuditMetaObjectHandler.SYSTEM);
    }

    @Test
    @DisplayName("★★ 插入时已显式给了 createdBy 的，不被覆盖——数据迁移会自带作者")
    void insertKeepsExplicitCreatedBy() {
        AuditMetaObjectHandler h = new AuditMetaObjectHandler();
        Probe e = new Probe();
        e.setCreatedBy("MIGRATION_V47");

        h.insertFill(metaOf(e));

        assertThat(e.getCreatedBy())
                .as("insert 用的是 strict 策略（只填空值），迁移脚本自带的作者要保住")
                .isEqualTo("MIGRATION_V47");
    }
}
