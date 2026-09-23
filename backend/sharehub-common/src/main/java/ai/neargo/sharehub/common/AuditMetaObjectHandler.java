package ai.neargo.sharehub.common;

import ai.neargo.sharehub.auth.SecurityUtils;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充（[实现状态总表] T0.9）：{@code createdAt/createdBy/updatedAt/updatedBy}
 * 由本处统一写入，业务代码<b>不要</b>手写这四个字段。
 *
 * <p><b>为什么必须自动填充而不是各处手写</b>：审计字段的价值在于「无一例外」。
 * 只要有一处写入路径漏填，事后查「这行是谁改的」就会得到 NULL —— 而恰恰是出问题的那次操作
 * 最可能走的是不常用的代码路径。靠人记住 = 迟早漏。
 *
 * <p><b>取值口径</b>：{@link SecurityUtils#userNo()} —— 运营端是 {@code employee_no}、
 * 代理端是 {@code account_no}、C 端是 {@code c_user_no}。三类主体共用一列，
 * 靠 {@code realm} 区分语义（同一个字符串不会跨池重复，因为业务键前缀不同）。
 *
 * <p><b>无登录主体时写 {@link #SYSTEM}</b> —— 定时任务、数据迁移、设备事件驱动的写入
 * 都没有登录态。这些操作照样要留痕，写 {@code SYSTEM} 比留 NULL 好：
 * NULL 无法区分「系统写的」与「漏填了」。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /** 无登录主体（定时任务 / 迁移 / 设备事件）时的审计人取值。 */
    public static final String SYSTEM = "SYSTEM";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String who = currentActor();

        // strictInsertFill：字段不存在于该实体时静默跳过，故 append 表（无 updated_*）也安全
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createdBy", String.class, who);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedBy", String.class, who);
    }

    /**
     * 更新填充：<b>无条件覆盖</b>。
     *
     * <p>⚠️ 这里<b>不能</b>用 {@code strictUpdateFill} —— 它走的是
     * {@code strictFillStrategy}，只在<b>字段为 null 时</b>才填：
     *
     * <pre>
     * if (metaObject.getValue(fieldName) == null) { ... }
     * </pre>
     *
     * <p>而真实的更新路径是「查出实体 → 改字段 → updateById」，查出来的 {@code updatedBy}
     * <b>一定有值</b>，于是这两行永远被跳过。结果是 {@code updatedBy} 停在第一次写入时的
     * 那个人，之后无论谁改都不变。
     *
     * <p><b>这个缺陷没有任何症状</b>：页面照常保存、接口照常返回 200，只有事后查
     * 「这行是谁改的」时才会发现答案一直是错的 —— 而那时已经查不回来了。
     * 2026-09-23 修复，守卫见 {@code AuditFillTest}。
     *
     * <p>{@code setFieldValByName} 内部有 {@code hasSetter} 判断，
     * 所以 append 表（没有 {@code updated_*} 列）依旧安全，与 insert 侧的 strict 策略同理。
     *
     * <p><b>insert 侧刻意保留 strict</b>（只填空值）：数据迁移与灰度脚本会自带
     * {@code createdBy}，那是有意的作者信息，不该被 {@code SYSTEM} 盖掉。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        setFieldValByName("updatedBy", currentActor(), metaObject);
    }

    private String currentActor() {
        String no = SecurityUtils.userNo();
        return (no == null || no.isBlank()) ? SYSTEM : no;
    }
}
