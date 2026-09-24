package ai.neargo.sharehub.platform.org.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditDetail;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditLogEntry;

/**
 * 操作审计（WORM）。
 *
 * <p><b>契约上只有「查」和「追加」</b> —— 没有 update、没有 remove，也不会有。
 * 审计的价值全部来自不可篡改；一旦开了改口子，事后就无法证明任何一行是原始记录。
 * 需要"更正"时的正确做法是**再追加一条**说明性记录，而不是改旧行。
 */
public interface AuditLogService {

    PageResult<AuditLogEntry> page(Integer page, Integer size, String keyword,
                                   String actor, String action, String targetType);

    /**
     * 单条详情（点开列表行时拉取）。
     *
     * <p><b>返回 null 表示「库里没有这一行」而不是异常</b>：列表当前仍由内存种子
     * {@code SeedData} 提供（id 形如 {@code A9000}，非自增数字），控制器需要「库里没有就回落种子」，
     * 用 null 表达比抛异常更好写 —— 抛异常会迫使控制器 try/catch 做流程控制。
     */
    AuditDetail detail(String id);

    /**
     * 追加一条审计。调用方（各域 service/切面）在完成写操作后调用。
     *
     * @param clientCode 从哪个端发起（OPS/AGENT/MP）。**必须由服务端从会话 realm 派生**，
     *                   不能来自请求头 —— 能被被审计方设置的审计字段比没有更糟。
     * @param detail     脱敏摘要（JSON 文本）；**不得放明文手机号/密钥**
     */
    void append(String actor, String actorName, String clientCode, String action,
                String targetType, String targetNo, String detail, String ip);
}
