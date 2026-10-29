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
    /**
     * 开通代理端登录的入参。**三项都可不传** —— 不传就用主体档案上的联系手机 / 邮箱 / 联系人，
     * 那是常态；让调用方再抄一遍只会抄错。
     *
     * <p>用 record 而不是裸 {@code Map}：裸 Map 等于没有契约，
     * 前端发错键名不报错、只是那一项静默为空（`MapRequestBodyRatchetTest` 盯这件事）。
     */
    public record GrantLoginReq(String phone, String email, String displayName) {
    }

    public record Agent(String agentNo, String name, String contact, String regionScope,
                       String agentType, Double shareRate, Integer cabinetCount, String status,
                       String archivedAt,
                       /* V123：联系方式从自由文本 contact 拆出来。contact 已退役，仅带出待归位的存量 */
                       String contactName, String phone, String email,
                       /*
                        * V157：商家核心资料「位置、行业」+ 系统建的默认站点。追加在末尾，理由同 LocDtos.Site。
                        * 保存时**不传 = 不改**：这几项多由运营后补（需求方案 D4 两阶段），
                        * 只改联系人的保存请求不该把位置清空。
                        */
                       String address, java.math.BigDecimal lng, java.math.BigDecimal lat, String industry,
                       String defaultSiteNo,
                       /* V183（C10）：VAT 税号。保存时不传 = 不改；传空串 = 清掉（商家注销了 VAT 登记） */
                       String vatTrn) {

        /** V157 之前的形状：位置与行业一律「不改」。种子与旧用例用它，免得十几个位置参数里塞五个 null。 */
        public Agent(String agentNo, String name, String contact, String regionScope,
                     String agentType, Double shareRate, Integer cabinetCount, String status,
                     String archivedAt, String contactName, String phone, String email) {
            this(agentNo, name, contact, regionScope, agentType, shareRate, cabinetCount, status, archivedAt,
                    contactName, phone, email, null, null, null, null, null, null);
        }

        /** 核心资料是否齐全：位置（地址 + 坐标）与行业。店名、联系人建账号时已必填。 */
        public boolean coreProfileComplete() {
            return address != null && !address.isBlank() && lng != null && lat != null
                    && industry != null && !industry.isBlank();
        }
    }
}
