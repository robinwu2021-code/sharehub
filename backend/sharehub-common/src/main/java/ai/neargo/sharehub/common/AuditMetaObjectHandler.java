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

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updatedBy", String.class, currentActor());
    }

    private String currentActor() {
        String no = SecurityUtils.userNo();
        return (no == null || no.isBlank()) ? SYSTEM : no;
    }
}
