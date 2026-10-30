package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表列的写入侧**待核实**台账 —— 只准变短，且新列必须当场表态。
 *
 * <h2>为什么需要这一条（事后卡口答不了的那个问题）</h2>
 * {@code StoredValueInVocabularyTest} 比对**库里已存的值**与列注释，是兜底。
 * 但脏数据必须**先落库**才会被它发现 —— 而那时该查的是当初写它的那次请求，现场早没了。
 * 2026-09-27 一天之内串错三次（V82 告警等级 / V84 对账差异 / V85 活动类型与拉黑原因），
 * 三次的写入方都绕开了校验。
 *
 * <p>最值钱的一条实证：{@code share_rule.mode} / {@code share_record.mode} 曾有 17 行
 * {@code RATE}（V66 归一），写它们的是 {@code ShareServiceImpl.saveRule} ——
 * 而 {@code ShareMode.of()} <b>那时已经存在、只是这里没调</b>。
 * 词表有定义、校验有实现、中间那一步没接上，<b>三者都不报错</b>。
 *
 * <h2>这一条守的不是「全都接上」，是「没有一列是悄悄多出来的」</h2>
 * 288 列的入口不可能一次接完，所以本条**不要求全覆盖**，要求两件事：
 * <ol>
 *   <li><b>新增词表列必须当场表态</b> —— 要么接上校验，要么进台账并写明为什么先不接。
 *       这正是那三次串错的共同形状：列加上了，谁都没想起写入侧；</li>
 *   <li><b>未覆盖数只准降</b> —— 接一个删一行，不许反向增长。</li>
 * </ol>
 *
 * <h2>为什么不自动判「这一列接没接」</h2>
 * 想过从调用链推（实体 setter ← 请求体 ← 有没有过 {@code of()}），但那要跨
 * 控制器 / service / mapper 三层做数据流分析，而本仓的写入路径有三种形态
 * （新旧两个对象、就地 set、部分更新）。判不准的卡口比没有更糟 ——
 * 它会让人以为「绿了就是接上了」。
 *
 * <p>所以覆盖与否由**人写进台账**，真正的证明在 {@code VocabularyRejectedOnWriteTest}
 * （从端点验：POST 词表外的值必须 400，每格带正对照）。本条只管账对不对得上。
 */
@SpringBootTest
@DisplayName("词表列写入侧覆盖台账")
class VocabularyWriteCoverageTest {

    private static final String LEDGER = "known-unvalidated-vocab-columns.txt";

    /**
     * **已核实写入侧有校验**的列。两种来源，都算：
     * <ul>
     *   <li>在 {@code VocabularyRejectedOnWriteTest} 里逐格验过（最好 —— 以后改坏会红）；</li>
     *   <li>读代码确认过写入口调了枚举的 {@code of()}（如 {@code AlarmCodeServiceImpl}
     *       那 8 列）。</li>
     * </ul>
     *
     * <p><b>建账时我把这两件事混了</b>：先只按第一种推，于是 {@code dev_alarm_code}
     * 另外 7 列（domain / subjectType / evalType / disposition / mergeScope /
     * recoverRule / basePriority）被误记成「没接」—— 而它们在
     * {@code AlarmCodeServiceImpl#beforeSave} 里挨个 of 过。
     * 台账的数字一旦虚高就没人信，而那是它唯一的价值。
     *
     * <p>写在这里而不是从那个测试类反射提取：用例名是给人读的句子，
     * 解析它等于把断言的可读性和卡口的正确性绑在一起 —— 改个标题就可能让统计变样。
     */
    private static final Set<String> COVERED = new TreeSet<>(Set.of(
            // V212/V218：技术步骤仅由InventoryOperationJournal固定值写入，不接收客户端phase/status。
            // engine只在物料编排传SERVICE；数据库CHECK再兜底，InventoryExecutionSchemaTest验证非法值拒绝。
            // V220: internal enums only; invalid SQL rejected in InventoryUnifiedApprovalTest.
            "inv_count_review.execution_state", "inv_count_review.decision",
            "inv_operation.engine", "inv_operation.phase", "inv_operation_step.status",
            "share_rule.dimension", "share_rule.mode", "share_rule.basis",
            "share_record.mode", "mkt_campaign.kind", "pay_channel.mode",
            "agt_commission.mode", "notify_blacklist.reason", "notify_blacklist.channel",
            "notify_template.channel", "usr_free_whitelist.reason", "usr_free_whitelist.quota_type",
            "md_problem.category", "md_problem.suggested_action",
            // dev_alarm_code：8 列都在 AlarmCodeServiceImpl#beforeSave 里逐个 of 过，
            // 其中只有 level 另有端点级用例（V82 白屏那一列）
            "dev_alarm_code.level", "dev_alarm_code.domain", "dev_alarm_code.subject_type",
            "dev_alarm_code.eval_type", "dev_alarm_code.disposition", "dev_alarm_code.merge_scope",
            "dev_alarm_code.recover_rule", "dev_alarm_code.base_priority",
            // 2026-09-28 接上：req.status() 原先原样落库（InvoiceServiceImpl），
            // 现在走 InvoiceStatus.of() 并归一大小写，VocabularyRejectedOnWriteTest 两格
            "fin_invoice.status",
            // ——— 以下是第三种来源：**前端根本传不了这一列**（见 SERVER_SET 说明）———
            "stl_payout_account.payee_type", "stl_adjustment.payee_type",
            "share_record.payee_type", "stl_settlement.payee_type",

            // ——— 2026-09-28 第二批：从 known-entity-writable-fields 的交集查起 ———
            // 那份台账记的是「实体当请求体」上客户端可写的字段。与本清单取交集，
            // 得到 18 个**前端直接写得进去、且没有任何 setXxx 拦在中间**的词表列 ——
            // 风险最高的一批，而且是自动算出来的，不靠谁记得。
            //
            // 新接上校验（各带端点格）：
            "iam_dept.status", "iam_employee.status",
            "dev_code_batch.status", "dev_code_batch.code_type",
            "loc_lead.share_mode", "md_problem.status", "usr_invoice_title.type",
            // 读代码核实：原来就有校验，只是建账时没人核过（台账虚高的那部分）
            "ad_slot.status",                                    // AdSlotStatus.of
            "notify_template.status",                            // NotifyTemplateStatus.of
            "dev_alarm_rule.channel", "dev_alarm_rule.method", "dev_alarm_rule.status",
            "wo_sla_rule.wo_type",                               // WorkOrderType.of
            "wo_sla_rule.priority",                              // WoPriority.of，外加「*」= 类型默认的哨兵（SlaRuleServiceImpl）
            "loc_lead.stage", "loc_lead.owner_type",             // checkStage / checkOwnerType
            // 服务端全权（第三种答案）：创建写常量、编辑抄 current、状态迁移走专门端点
            "notify_blacklist.status", "usr_free_whitelist.status",

            // ——— 2026-09-28 第三批：订单域与设备 OTA ———
            // 新接上校验：OTA 四列（各带端点格）——原先只在**空的时候**补默认值，
            // 传了词表外的值照样落库。scope 错=打给错的一批设备，strategy 错=该灰度的全量推了出去，
            // 而固件是刷进硬件里的，发现时已经刷完
            "dev_ota_release.status", "dev_ota_rollout.status",
            "dev_ota_rollout.strategy", "dev_ota_rollout.scope",
            "ord_deposit.last_dun_channel",                      // DunChannel.ofNullable（无端点格：ord_deposit 造数据太贵）
            // 读代码核实：原来就有校验
            "ord_complaint.issue_type",                          // ISSUE_TYPES 白名单
            "ord_complaint.resolution",                          // RESOLUTIONS 白名单（在 setResolution 之前，只看 setter 那行会误判）
            "ord_exception.handle_action",                       // switch + default -> throw
            // 服务端常量 / 状态机
            "ord_complaint.status", "ord_deposit.status", "ord_exception.status",
            "ord_order.pay_status", "ord_order.source_channel",
            "ord_refund.status", "ord_reservation.status",

            // ——— 2026-09-28 第四批：告警域 + 设备域 ———
            // 告警域 18 列几乎全是「服务端全权」：引擎内部用枚举 .name() 写，
            // 人工关闭走 AlarmCloseReason.of()，通知渠道抄自 dev_alarm_rule.channel（已覆盖），
            // 时间线 event 是 private writeLog 的字面量
            "dev_alarm.cause", "dev_alarm.close_reason", "dev_alarm.disposition_type",
            "dev_alarm.domain", "dev_alarm.impact_period", "dev_alarm.impact_scope",
            "dev_alarm.level", "dev_alarm.priority", "dev_alarm.source",
            "dev_alarm.status", "dev_alarm.subject_type",
            "dev_alarm_log.event", "dev_alarm_notice.channel", "dev_alarm_notice.status",
            "dev_alarm_route.disposition", "dev_alarm_route.fallback",
            "dev_alarm_route.wo_type",                           // 2026-09-28 补：原先只在 disposition=WORK_ORDER 时才校验
            "dev_alarm_todo.status",
            // 设备域：建档写常量、状态走状态机、质检结果是枚举
            "dev_cabinet.status", "dev_cabinet.device_type", "dev_cabinet.qc_status",
            "dev_cabinet.online_status",                         // 建档 OFFLINE，转 ONLINE 在 DeviceSignalServiceImpl
            "dev_powerbank.status",
            "dev_powerbank.health",                              // 2026-09-28 接上 PowerbankHealth（原先原样落库，而它参与可借判定）
            "dev_protection.action", "dev_protection.holder_type",
            "dev_qc_record.item_type", "dev_qc_record.result",
            "dev_trial_rent.status",

            // ——— 2026-09-28 第五批：合同域 + 工单域 ———
            // 新接上校验（各带端点格）：
            "loc_contract.settle_period",                        // 写错 = 保底永远不补差，只留一行 WARN
            "wo_order.audit_result",                             // 它驱动「FAIL 不许关单」那道闸，原先却不校验
            // 读代码核实：白名单 / 枚举 / 服务端常量
            "loc_contract.share_base", "loc_contract.share_mode", "loc_contract.contract_kind",
            "loc_contract.status", "loc_contract.audit_stage", "loc_contract.term_req_status",
            "loc_contract_log.event", "loc_contract_log.from_status", "loc_contract_log.to_status",
            "wo_order.type", "wo_order.source", "wo_order.priority",   // WorkOrderType.of / SOURCES 白名单 / WoPriority.of
            "wo_order.status", "wo_order.review_status",
            "wo_order.close_reason",                             // CLOSE_REASONS 白名单
            "wo_order.assignee_type",                            // assigneeTypeOf 只返回 AGENT/EMPLOYEE/null
            "wo_order.dispatch_strategy", "wo_order.cost_bearer_type",
            "wo_order.fault_reason_code", "wo_handle.fault_reason_code",  // WoFaultReason.of
            "wo_dispatch.action", "wo_dispatch.strategy",

            /*
             * 本条卡口当场抓到的第一个「新列」：stl_withdrawal.approval_state（另一会话的 V143，
             * 提现接 OpsKit 审批）。这正是它要守的形状 —— 列加上了、谁都没想起写入侧。
             * 查证后归入「服务端全权」：WithdrawalServiceImpl 与 WithdrawalApprovalOutcome
             * 里全部写 WithdrawalApprovalState.*.name() 常量或 null，申请入参 WithdrawApplyReq
             * 不含这个字段，前端传不了。
             *
             * <p><b>⚠️ 这个结论有一个前提，写在这里以免它哪天悄悄失效</b>：
             * 这一列的值来自 OpsKit 的审批回调，而回调体里带的是 {@code result}
             * （APPROVED / REJECTED / WITHDRAWN），<b>不是 approval_state 的取值</b>。
             * 接收端必须把 {@code result} 映射成枚举常量再写库 —— 一旦有人图省事把
             * webhook 里的字符串原样 {@code setApprovalState(d.result())}，
             * 「服务端全权」就不成立了，这一行要从 COVERED 挪回台账并接上校验。
             * 2026-09-28 复核过：{@code onCompleted} 的分支走的是 {@code d.approved()}
             * 这个布尔，{@code d.result()} 只参与 {@code "WITHDRAWN".equals(...)} 挑措辞，
             * 没有进过这一列。
             */
            "stl_withdrawal.approval_state",
            // ord_tax_invoice（V181）：无任何请求体能写它，kind/status 只由 TaxInvoiceServiceImpl 用常量赋值
            "ord_tax_invoice.kind", "ord_tax_invoice.status",
            // ops_approval_request（V189）：请求体里没有这两列，kind/status 只由 ApprovalGate 用常量赋值
            "ops_approval_request.kind", "ops_approval_request.status",
            // sys_param.value_type（V191）：只由迁移写；SysParamServiceImpl.save 的 lockDictionary 以库里为准覆盖请求体
            "sys_param.value_type",
            // inv_intake / inv_intake_item（V192 到仓待检）：kind / status / item_type 只由 IntakeServiceImpl 用常量赋值
            //（登记入参是 cabinetNos / powerbankNos 两个列表，类型由列表决定）；qc_result 取 QcService 的结论（QcStatus.name()）；
            // disposition 来自请求体，但写库前经 DISPOSALS 白名单（RETURN_SUPPLIER / SCRAP），不在白名单 400
            "inv_intake.kind", "inv_intake.status",
            // V202：currency 是 ISO 4217 币种码，服务端只做 trim + 大写，不是枚举
            "inv_intake.currency",
            // inv_disposal / inv_disposal_item（V203 处置出库）：kind / reason_code 经 DisposalKind / DisposalReason 校验，item_type 用常量
            "inv_disposal.kind", "inv_disposal.reason_code", "inv_disposal_item.item_type",
            // V204 安全库存：item_type 经 StockThresholdServiceImpl.TYPES 白名单校验
            "inv_stock_threshold.item_type",
            // V205 发货物流：doc_type 用 ShipmentDocType 常量，item_type 来自所属单据
            "inv_shipment.doc_type", "inv_shipment_item.item_type",
            // V210 物料：category / tracking_mode / kind / purpose / reason_code 经 MaterialCategory / MaterialTracking /
            // MaterialDocPurpose（kind 由用途派生）/ DisposalReason 校验；device_type 只由迁移种入
            "inv_material.category", "inv_material.tracking_mode", "inv_material.device_type",
            "inv_material_doc.kind", "inv_material_doc.purpose", "inv_material_doc.reason_code", "inv_material_doc.currency",
            "inv_intake_item.item_type", "inv_intake_item.qc_result", "inv_intake_item.disposition",
            // dev_movement_log（V196 设备履历）：event 只由 MovementEvent 枚举的 name() 写入，item_type 只取 MovementRecorder 常量
            // 或待检单明细已校验的类型；没有外部写入口（Mapper 只许 MovementRecorder 用，ArchitectureTest 守）
            "dev_movement_log.event", "dev_movement_log.item_type",
            // inventory_inbox.status（V198）：只由 InventoryEventPuller 用 InboxStatus 枚举写，无外部写入口
            "inventory_inbox.status",

            // ——— 2026-09-28 第六批：钱那一侧剩下的 21 列，全部是「读代码核实」，一处新缺口都没有 ———
            // 这一批一个真缺口都没查出来，值得记一笔：finance 域的守法很统一 ——
            // 要么枚举 .name()、要么写在方法开头的白名单（ACTION_RESULT / PAY_CHANNELS /
            // DEBIT-CREDIT 的 else-throw），而白名单**离 setter 有十几行远**。
            "share_record.basis",                    // 派生自 share_rule.basis（已覆盖）/ 常量 REFER
            "share_record.base_mode",                // V155：ShareGeneratorImpl 写 ShareBaseMode.name()；参数值写坏时 parse 回落 PRE_TAX，无外部写入口
            // ——— 商家一期（V157 / V158）———
            "agt_agent.industry",                    // AgentServiceImpl.applyCoreProfile 调 VenueIndustry.of，词表外 400；正反对照见 Phase1MerchantProfileTest
            "stl_payout_task.status",                // PayoutTaskServiceImpl 只写 PayoutTaskStatus.name()，无外部写入口
            "stl_withdrawal.origin",                 // 只由 PayoutTaskServiceImpl 写 WithdrawalOrigin.PAYOUT_TASK.name()
            "stl_payout_export.format",              // 只写常量 CSV（一期唯一导出格式），无外部写入口
            "wo_order.removal_disposition",          // V164：WoOpsServiceImpl.create 调 RemovalDisposition.ofNullable，词表外 400；只对撤机单写
            "stl_withdrawal.pay_channel",            // PAY_CHANNELS 白名单（在 setPayChannel 上面五行）
            "acct_ledger.direction",                 // DEBIT/CREDIT，校验在写入循环之前，非法值 else-throw
            "usr_wallet_txn.direction",              // amount.signum() > 0 ? IN : OUT
            "recon_diff.diff_type", "recon_diff.handle_result",
            "recon_task.handle_result", "recon_task.handle_status", "recon_task.status",
            "stl_adjustment.kind", "stl_adjustment.source", "stl_adjustment.status",
            "stl_settlement.status", "stl_settlement_detail.ref_type", "stl_payout_account.status",
            "stl_withdrawal.status", "usr_recharge_order.status", "fin_invoice.source_type",

            /*
             * pay_auth / pay_order / pay_refund 的 status 有**同一个前提**，写在这里：
             * 它们落的是 `r.status()`，而 r 是 {@code PaymentPort} 的返回值 ——
             * 不是请求体，是**支付驱动**给的。当前 StubPaymentPort 返的就是我们自己的枚举名，
             * 所以成立；接 nearpay 之后，<b>那个 Port 实现必须把渠道方的状态映射成本仓词表</b>，
             * 原样透传就等于让外部系统往我们的状态列里写值，这三行要挪回台账。
             * 与 stl_withdrawal.approval_state 是同一类前提：结论属于「当前写法」，不属于这一列。
             */
            "pay_auth.status", "pay_order.status", "pay_refund.status",

            // ——— 2026-09-28 第七批：代理域 + 库存域 18 列，同样一个真缺口都没有 ———
            // 又是三处「白名单离 setter 有十几行远」：AgentAssignmentServiceImpl 的
            // TARGET_TYPES / ACTIONS、ApplyServiceImpl 的 OPERATOR_TYPES、
            // InventoryTransferServiceImpl 的 ITEM_TYPES（在 validateEndpoints 里）。
            "agt_agent.agent_type",                  // AgentType.of
            "agt_agent.status", "agt_account.status", "agt_principal.status",
            "agt_apply.status", "agt_exit.status", "agt_commission.status",
            "agt_commission.dimension",
            "agt_apply.source",                      // 常量 OPS_CREATED / SELF_SERVICE
            "agt_apply.operator_type",               // OPERATOR_TYPES 白名单
            "agt_assignment.action", "agt_assignment.target_type",   // ACTIONS / TARGET_TYPES 白名单
            "inv_transfer.item_type",                // ITEM_TYPES 白名单（validateEndpoints）
            "inv_transfer.source_type", "inv_transfer.status",
            "inv_asset_diff.item_type",              // 派生自已校验的调拨单 / 常量 POWERBANK
            "inv_asset_diff.kind", "inv_asset_diff.source_type", "inv_asset_diff.status",

            /*
             * cs_ticket.channel：2026-09-28 接上 CsTicketChannel。
             * 它是被**另一条卡口**推出来的 —— 本类第三条用例（台账里不许有已经不存在的列）
             * 突然红了，查下来是 CsTicketCreateTest 往库里写了一行 channel='电话'，
             * 而那一个异形值让这一列不再满足「取值全是大写形」，于是从词表列清单里掉了出去。
             * 顺着查到写入侧根本没校验。详见 CsTicketChannel 与
             * StoredValueInVocabularyTest 里新加的「混形列」那条。
             */
            "cs_ticket.channel"));

    /*
     * ⚠️ 第五批又证了一次「只 grep setXxx 会判错」，而且是反方向：
     * wo_order 有七列**一个 setXxx 都搜不到**（assignee_type / audit_result / close_reason /
     * cost_bearer_type / dispatch_strategy / fault_reason_code / review_status），
     * 差点被当成「死列」—— 它们是用 `UpdateWrapper.set("列名", ...)` 按**列名**写的。
     * 实体 setter 与 UpdateWrapper 列名是两条写入路径，查一条不够。
     */

    /*
     * ⚠️ 第三批的方法论教训：**只 grep `setXxx(` 那一行会得出错的结论**。
     * ord_complaint.resolution 的 setter 写的是 `e.setResolution(req.resolution())`，
     * 看起来是「请求体直达」，而白名单 `RESOLUTIONS.contains(...)` 在它上面几行。
     * 自动分类只能用来**排优先级**，判定必须读方法。
     *
     * ⚠️ 查这一批时撞见三个**没有任何写入路径**的表/列，它们留在台账上没有移走 ——
     * 「没人写」在这里不等于安全，等于这个功能没有生产者，等哪天补上写入口时
     * 照样要接校验：
     *   · ord_exception：create(...) 存在但**零调用方**，异常单永远产生不了；
     *   · ord_reservation：ReservationServiceImpl 只有 page/cancel，**一处 insert 都没有**；
     *   · dev_ota_task：只读，没有任何东西创建 OTA 任务。
     * 三张表在测试库里都是 0 行，与这个结论一致。实现状态总表把订单域记成
     * 「异常/投诉/退款/预约齐」，而其中两项在生产上是永远空的页面。
     */

    /*
     * 核实一列时会遇到第三种答案，它和「接上了校验」一样牢靠，有时更牢靠：
     * **这一列压根不来自请求体**。上面那四个 payee_type 就是：
     *
     *   · stl_payout_account.payee_type —— PayoutAccountServiceImpl 有白名单
     *     PAYEE_TYPES（AGENT/VENUE），不在其中直接 400。这是真的校验；
     *   · stl_adjustment.payee_type —— AdjustmentServiceImpl 两处都写死常量 VENUE；
     *   · share_record.payee_type —— ShareGeneratorImpl 抄自 share_rule.dimension
     *     （那一列已在本清单里），ReferFeeGeneratorImpl 写死 AGENT；
     *   · stl_settlement.payee_type —— SettlementServiceImpl 从上面两张表的表头抄。
     *
     * 判据是 grep 全部 setPayeeType 的写入点（8 处，全在 svc-finance），逐个看来源。
     * 「派生自另一个已覆盖的列」要连着上游一起看：dimension 哪天松了，这四列跟着松。
     *
     * ⚠️ 顺带查出来两件不在本卡口范围内的事，记在这儿免得下次重查：
     *   · stl_withdrawal.payee_type **既没注释也没校验**，WithdrawalServiceImpl
     *     直接 e.setPayeeType(req.payeeType())。没注释 = 本卡口看不见它；
     *   · fin_invoice.payee_type 的注释是占位文字「既有DDL同名列×3」，同样看不见。
     * 两个都该补注释（那是 DdlStatusVocabularyCommentTest 的地盘），补完会自动进本清单。
     */

    @Autowired
    JdbcTemplate jdbc;

    private Set<String> cache;

    @Test
    @DisplayName("★★ 每个词表列要么已接校验、要么在台账里 —— 不许悄悄多出一列没人管的")
    void every_vocabulary_column_is_either_covered_or_on_the_ledger() throws IOException {
        Set<String> all = vocabularyColumns();
        assertThat(all).as("前提：读得到词表列（读不到会让本条恒绿）").hasSizeGreaterThan(200);

        Set<String> ledger = readLedger();
        Set<String> unaccounted = new TreeSet<>(all);
        unaccounted.removeAll(COVERED);
        unaccounted.removeAll(ledger);

        assertThat(unaccounted)
                .as("""
                        这些词表列**既没接写入侧校验、也不在台账里** —— 多半是新加的列忘了表态。
                        二选一：
                          · 写入口调枚举的 ofNullable()（传了才校验），并往
                            VocabularyRejectedOnWriteTest 加一格（带正对照），再把它加进 COVERED；
                          · 或者把它加进 %s，并写明为什么先不接。
                        别指望事后那条 StoredValueInVocabularyTest 替你兜着 ——
                        它要等脏数据先落库，而那时现场已经没了。
                        """.formatted(LEDGER))
                .isEmpty();
    }

    @Test
    @DisplayName("★ 台账里不许有已经接上的列 —— 接了就要删行，否则数字永远降不下来")
    void the_ledger_does_not_list_already_covered_columns() throws IOException {
        Set<String> stale = new TreeSet<>(readLedger());
        stale.retainAll(COVERED);
        assertThat(stale)
                .as("这些列已经接上校验了，请从 %s 删掉对应行 —— 留着会让「未覆盖数」虚高，"
                        + "而这个数字是唯一能说明进度的东西", LEDGER)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 台账里不许有已经不存在的列 —— 删表改列之后要跟着清")
    void the_ledger_does_not_list_columns_that_no_longer_exist() throws IOException {
        Set<String> all = vocabularyColumns();
        Set<String> ghosts = new TreeSet<>(readLedger());
        ghosts.removeAll(all);
        assertThat(ghosts)
                .as("""
                        这些行在库里已经找不到对应的词表列了（列删了 / 改名了 / 注释改得不再声明词表）。
                        留着它们会让未覆盖数虚高 —— 而那个数字一旦不可信，
                        这份台账就退化成了一张没人看的清单。
                        """)
                .isEmpty();
    }

    // ───────────────────────── 读取 ─────────────────────────

    /**
     * 写入侧要管的词表列。判据与 {@code StoredValueInVocabularyTest} 共用
     * （{@link VocabularyColumns}），<b>唯一的分歧是空列</b>：
     * 库里还没有取值时形状判不出来，事后卡口跳过它，本条保留它 ——
     * 新建的空表恰恰是最该当场表态的。理由写在 {@code VocabularyColumns} 类注释里。
     *
     * <p>一次扫描要对每一列各查一次取值分布，三条用例共用一份结果。
     */
    private Set<String> vocabularyColumns() {
        if (cache == null) {
            Set<String> out = new TreeSet<>();
            for (VocabularyColumns.Column c : VocabularyColumns.scan(jdbc)) {
                if (c.looksLikeEnum() || c.hasNoValues() || c.classicName()) out.add(c.key());
            }
            cache = out;
        }
        return cache;
    }

    private Set<String> readLedger() throws IOException {
        Path p = backendRoot().resolve(LEDGER);
        Set<String> out = new TreeSet<>();
        List<String> bad = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!t.matches("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")) {
                bad.add(line);
                continue;
            }
            out.add(t);
        }
        assertThat(bad).as("台账行格式应为 `表.列`（注释用 # 开头）").isEmpty();
        return out;
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve(LEDGER))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根（含 " + LEDGER + "）");
        return p;
    }
}
