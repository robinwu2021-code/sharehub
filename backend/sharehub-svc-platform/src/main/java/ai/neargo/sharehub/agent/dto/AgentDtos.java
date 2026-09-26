package ai.neargo.sharehub.agent.dto;

import java.util.List;
import java.math.BigDecimal;

/**
 * agent 域的出入参 DTO。
 *
 * <p>原先住在顶层 {@code dto.Dto} 那个骨架期共享集合里 —— 那让 agent 域**必须依赖 app**，
 * 是拆 Maven 模块的硬阻塞。DTO 属于它描述的那个域，搬回来是归位不是重构。
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /**
     * @param agentType 登记类型 AGENT / CITY_PARTNER（ADR-027）；存量与缺省为 AGENT
     */
    /** {@code archivedAt}：归档时间，`null` = 在用。运营端靠它把归档行置灰并显示归档时间。 */
    /**
     * 代理商档案。出入参同形。
     *
     * <p><b>{@code shareRate} / {@code cabinetCount} 是包装类型，不是原始类型</b>：
     * record 上的原始类型字段在 Jackson 里**不能缺** —— 缺了直接 400，
     * 而错误信息说的是反序列化失败，看不出少了哪个字段。
     * 于是「新增代理商」这种只填名称与联系方式的请求根本发不出去。
     *
     * <p>{@code cabinetCount} 尤其没理由必填：它是**只出不入的聚合值**
     *（名下有几台机柜，服务端现算、保存时不回写），要求调用方带上它等于
     * 让人填一个填了也不算数的数。
     */
    public record Agent(String agentNo, String name, String contact, String regionScope,
                       String agentType, Double shareRate, Integer cabinetCount, String status,
                       String archivedAt) {
    }
}
