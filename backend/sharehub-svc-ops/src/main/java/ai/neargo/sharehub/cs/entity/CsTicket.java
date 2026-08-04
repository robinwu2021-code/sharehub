package ai.neargo.sharehub.cs.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 报障受理单（cs_ticket，[db-design §6.5]）—— <b>C 端一切诉求的唯一受理单</b>（[api/README §6A.2]）。
 *
 * <p>v1 把「报障 / 投诉 / 工单 / 退款」四者各写各的，导致一个 C 端报障可能同时落进 cs_ticket 和
 * ord_complaint 而无从对账。v2 定稿为**单一入口 + 字典驱动分流**：
 *
 * <pre>
 * POST /mp/user/report ──▶ cs_ticket（必建）
 *                            │  按 md_problem.suggested_action 分流（不硬编码）
 *      ┌───────────┬─────────┼──────────┬───────────┐
 * SELF_SERVICE  TO_WORKORDER  TO_REFUND  TO_CS
 *   直接关单     wo_no 出口   refund_no 出口  转人工会话
 * </pre>
 *
 * <p><b>两个出口字段的不变式</b>：{@link #woNo} / {@link #refundNo} 一旦写入即锁定 ——
 * 转出动作必须幂等，字段非空则返回已有值，绝不建第二张单。这保证「一个诉求 → 处置去向可追溯且唯一」。
 *
 * <p>与 {@code ord_complaint} 的分工：本表粒度是**诉求**，ord_complaint 粒度是**订单争议**，
 * 二者 0..1 关联，不重复受理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cs_ticket")
public class CsTicket extends BaseEntity {

    private String ticketNo;

    /** 报障人 c_user_no。 */
    private String cUserNo;

    /** 关联租借订单；空 = 与具体订单无关的诉求。 */
    private String orderNo;

    private String cabinetNo;

    /** 问题字典 {@code md_problem.problem_no} —— 分流依据的来源，逻辑引用无物理 FK。 */
    private String problemNo;

    /** 用户自述。 */
    private String issue;

    /** APP / MP / H5 / PHONE / EMAIL。 */
    private String channel;

    /** OPEN / PROCESSING / CLOSED。 */
    private String status;

    /** 受理客服 employee_no。 */
    private String handlerNo;

    /** 出口①：转出的工单号。非空即已转，幂等锚点。 */
    private String woNo;

    /** 出口②：转出的退款申请号（{@code ord_refund.refund_no}）。非空即已转，幂等锚点。 */
    private String refundNo;
}
