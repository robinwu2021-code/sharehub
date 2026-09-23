package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper;
import ai.neargo.sharehub.alarm.mapper.DevAlarmMapper;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.finance.entity.*;
import ai.neargo.sharehub.finance.mapper.*;
import ai.neargo.sharehub.platform.org.entity.IamDept;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.mapper.IamDeptMapper;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.wo.ext.entity.WoInspectionPlan;
import ai.neargo.sharehub.wo.ext.entity.WoSlaRule;
import ai.neargo.sharehub.wo.ext.mapper.WoInspectionPlanMapper;
import ai.neargo.sharehub.wo.ext.mapper.WoSlaRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运维/财务/组织域演示种子（幂等，逐表判空各自灌）。
 *
 * <p><b>为什么需要</b>：这些域的端点在骨架退役后全部改读真表，但表从未有过种子 ——
 * ops-web 切 {@code USE_MOCK=0} 后告警/工单配置/对账/结算/发票/账务/员工六个页面是<b>白板</b>，
 * 「后端 200 且返回空列表」在页面上与「接口坏了」无法区分，联调时最耗时的就是这类假象。
 *
 * <p><b>逐表判空而非整体判空</b>：与 5 个域 seeder 的「有数据即整批跳过」不同，
 * 本类每张表独立判空 —— 它跨 6 个域，任何一张表先被业务写入都会让整批跳过，
 * 剩下的表永远补不上。
 *
 * <p>数量刻意少（各 3–8 行）：种子是为了让页面有东西看、让联调能点得动，不是造压测数据。
 */
@Component
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
@Order(7)
public class DemoOpsFinanceSeeder implements CommandLineRunner {

    private static final String TENANT = "MAIN";
    private static final String CCY = "AED";

    private final DevAlarmCodeMapper alarmCodes;
    private final DevAlarmMapper alarms;
    private final CabinetMapper cabinets;
    private final WoSlaRuleMapper slaRules;
    private final WoInspectionPlanMapper plans;
    private final ReconTaskMapper reconTasks;
    private final ReconDiffMapper reconDiffs;
    private final StlSettlementMapper settlements;
    private final StlSettlementDetailMapper settlementDetails;
    private final FinInvoiceMapper invoices;
    private final ShareRecordMapper shareRecords;
    private final ShareRuleMapper shareRules;
    private final AgentMapper agents;
    private final AcctLedgerMapper ledgers;
    private final AcctAccountMapper accounts;
    private final IamDeptMapper depts;
    private final IamEmployeeMapper employees;
    private final OrdMapper orders;

    public DemoOpsFinanceSeeder(DevAlarmCodeMapper alarmCodes, DevAlarmMapper alarms, CabinetMapper cabinets,
                                WoSlaRuleMapper slaRules, WoInspectionPlanMapper plans,
                                ReconTaskMapper reconTasks, ReconDiffMapper reconDiffs,
                                StlSettlementMapper settlements, StlSettlementDetailMapper settlementDetails,
                                FinInvoiceMapper invoices, ShareRecordMapper shareRecords,
                                ShareRuleMapper shareRules,
                                AgentMapper agents,
                                AcctLedgerMapper ledgers, AcctAccountMapper accounts,
                                IamDeptMapper depts, IamEmployeeMapper employees, OrdMapper orders) {
        this.alarmCodes = alarmCodes;
        this.alarms = alarms;
        this.cabinets = cabinets;
        this.slaRules = slaRules;
        this.plans = plans;
        this.reconTasks = reconTasks;
        this.reconDiffs = reconDiffs;
        this.settlements = settlements;
        this.settlementDetails = settlementDetails;
        this.invoices = invoices;
        this.shareRecords = shareRecords;
        this.shareRules = shareRules;
        this.agents = agents;
        this.ledgers = ledgers;
        this.accounts = accounts;
        this.depts = depts;
        this.employees = employees;
        this.orders = orders;
    }

    @Override
    public void run(String... args) {
        seedAlarms();
        seedWoConfig();
        seedFinance();
        seedOrg();
    }

    // ——————————————————————— 告警 ———————————————————————

    private void seedAlarms() {
        if (alarmCodes.selectCount(null) == 0) {
            insertCode("E001", "仓位卡宝", "Slot jammed", "فتحة معطلة", "HIGH", "远程弹仓一次，仍卡则派工单换锁扣", 1);
            insertCode("E002", "离线超时", "Device offline", "الجهاز غير متصل", "HIGH", "查网络/电源，30 分钟未恢复自动开单", 1);
            insertCode("E003", "电量异常", "Abnormal battery", "بطارية غير طبيعية", "MEDIUM", "标记该宝待检，下次巡检带走", 0);
            insertCode("E004", "温度过高", "Over temperature", "درجة حرارة مرتفعة", "URGENT", "立即断电并现场处置", 1);
        }
        if (alarms.selectCount(null) == 0) {
            List<DevCabinet> cabs = cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().last("limit 6"));
            String[] codes = {"E001", "E002", "E003", "E004", "E001", "E002"};
            String[] levels = {"HIGH", "HIGH", "MEDIUM", "URGENT", "HIGH", "HIGH"};
            String[] states = {"OPEN", "OPEN", "ACKED", "OPEN", "CLOSED", "OPEN"};
            for (int i = 0; i < cabs.size(); i++) {
                DevCabinet c = cabs.get(i);
                DevAlarm a = new DevAlarm();
                a.setAlarmNo("ALM" + (90000 + i));
                a.setTenantId(TENANT);
                a.setCabinetNo(c.getCabinetNo());
                a.setSiteNo(c.getSiteNo());
                a.setAgentNo(c.getAgentNo());
                a.setVendorCode(c.getVendorCode());
                a.setAlarmCode(codes[i]);
                a.setLevel(levels[i]);
                a.setSource("DEVICE");
                a.setOccurredAt(LocalDateTime.now().minusHours(i + 1L).toString());
                a.setStatus(states[i]);
                a.setDedupKey(c.getCabinetNo() + ":" + codes[i]);
                a.setCount(1);
                alarms.insert(a);
            }
        }
    }

    private void insertCode(String code, String zh, String en, String ar, String level, String suggestion, int auto) {
        DevAlarmCode e = new DevAlarmCode();
        e.setCode(code);
        e.setMessage(zh);
        e.setMessageEn(en);
        e.setMessageAr(ar);
        e.setLevel(level);
        e.setSuggestion(suggestion);
        e.setAutoWorkOrder(auto);
        alarmCodes.insert(e);
    }

    // ——————————————————————— 工单配置 ———————————————————————

    private void seedWoConfig() {
        if (slaRules.selectCount(null) == 0) {
            insertSla("SLA001", "FAULT", 30, 240);
            insertSla("SLA002", "REFILL", 60, 480);
            insertSla("SLA003", "INSPECT", 120, 1440);
            insertSla("SLA004", "COMPLAINT", 15, 120);
        }
        if (plans.selectCount(null) == 0) {
            // 路线取真实站点名，否则 /run 定位机柜必然扑空（一站找不到就整批拒）
            List<DevCabinet> cabs = cabinets.selectList(new LambdaQueryWrapper<DevCabinet>()
                    .isNotNull(DevCabinet::getLocationName).last("limit 4"));
            if (cabs.size() >= 2) {
                insertPlan("IP0001", cabs.get(0).getLocationName() + " → " + cabs.get(1).getLocationName(),
                        "每周", "0 0 9 * * MON", "Ali Hassan");
            }
            if (cabs.size() >= 4) {
                insertPlan("IP0002", cabs.get(2).getLocationName() + " → " + cabs.get(3).getLocationName(),
                        "每月", "0 0 9 1 * ?", "Omar Khan");
            }
        }
    }

    private void insertSla(String no, String type, int respond, int resolve) {
        WoSlaRule e = new WoSlaRule();
        e.setSlaNo(no);
        e.setTenantId(TENANT);
        e.setWoType(type);
        e.setResponseMins(respond);
        e.setResolveMins(resolve);
        e.setEscalateTo("ops-lead");
        e.setActive(1);
        slaRules.insert(e);
    }

    private void insertPlan(String no, String route, String freq, String cron, String assignee) {
        WoInspectionPlan e = new WoInspectionPlan();
        e.setPlanNo(no);
        e.setTenantId(TENANT);
        // route 是 JSON 列（V13，json_valid CHECK）：存数组，出参由 service 拼展示串
        e.setRoute(ai.neargo.sharehub.common.Json.write(java.util.Arrays.stream(route.split("→"))
                .map(String::trim).filter(x -> !x.isBlank()).toList()));
        e.setFrequency(freq);
        e.setCron(cron);
        e.setNextAt(LocalDate.now().plusDays(1).atTime(9, 0).toString());
        e.setAssigneeNo(assignee);
        e.setActive(1);
        plans.insert(e);
    }

    // ——————————————————————— 财务 ———————————————————————

    private void seedFinance() {
        String period = LocalDate.now().toString().substring(0, 7);

        /*
         * 分润规则必须挂在**真实存在的分成方**上。
         *
         * 此前这里写死了 VEN001 / AG001 —— 而实际种子里场地方是 VEN300+、代理是 AG001~AG009，
         * 站点上挂的也是 AG002/AG006 这些。于是规则看着有三条、任何一笔真实订单都命中不了，
         * 「分润算不出来」的原因藏在编号里，从界面上完全看不出来（2026-09-23 实测）。
         *
         * 现在按库里已有的代理逐个建 AGENT 规则。VENUE 维度**故意不建**：
         * 场地方分成以进场合同为准（见 ShareGeneratorImpl 的 D2 取舍），
         * 这里再建一份只会让「到底按哪个」重新变成一笔糊涂账。
         */
        if (shareRules.selectCount(null) == 0) {
            List<AgtAgent> all = agents.selectList(null);
            int i = 0;
            for (AgtAgent a : all) {
                if (a.getAgentNo() == null || a.getAgentNo().isBlank()) continue;
                insertShareRule("SHR" + String.format("%03d", ++i), "AGENT", a.getAgentNo(),
                        a.getName(), new BigDecimal("0.2000"), 10 + i);
            }
        }

        if (accounts.selectCount(null) == 0) {
            insertAccount("ACC0001", "PLATFORM", "MAIN", "CASH", new BigDecimal("125000.00"));
            insertAccount("ACC0002", "AGENT", "AG001", "PAYABLE", new BigDecimal("8600.00"));
            insertAccount("ACC0003", "VENUE", "VEN001", "PAYABLE", new BigDecimal("4200.00"));
        }

        // 分润流水挂真实订单号，页面点进去能对得上（挂造号会让「订单不存在」）
        List<OrdOrder> settled = orders.selectList(new LambdaQueryWrapper<OrdOrder>()
                .eq(OrdOrder::getStatus, "SETTLED").last("limit 8"));
        if (shareRecords.selectCount(null) == 0 && !settled.isEmpty()) {
            for (int i = 0; i < settled.size(); i++) {
                OrdOrder o = settled.get(i);
                ShareRecord r = new ShareRecord();
                r.setRecordNo("SR" + (10000 + i));
                r.setTenantId(TENANT);
                r.setOrderNo(o.getOrderNo());
                r.setDimension(i % 2 == 0 ? "VENUE" : "AGENT");
                r.setPayeeType(i % 2 == 0 ? "VENUE" : "AGENT");
                r.setPayeeNo(i % 2 == 0 ? "VEN001" : "AG001");
                r.setPayeeName(i % 2 == 0 ? "Dubai Mall 运营方" : "AG001 代理商");
                BigDecimal gross = BigDecimal.valueOf(o.getFeeAmount() == null ? 12 : o.getFeeAmount());
                r.setRate(new BigDecimal("0.35"));
                r.setGrossAmount(gross);          // 基数快照（V34）：统计不再由 amount/rate 反推
                r.setPeriod(period);              // 归属账期定格（V34）：不再由 created_at 现推
                r.setAmount(gross.multiply(new BigDecimal("0.35")).setScale(2, java.math.RoundingMode.HALF_UP));
                r.setCurrency(CCY);
                r.setMode("RATE");
                r.setStatus("PENDING");
                shareRecords.insert(r);
            }
        }

        if (ledgers.selectCount(null) == 0) {
            // 复式记账：每张凭证借贷成对，合计必须相等（不平的演示数据比没有更糟）
            insertVoucher("VCH0001", "ORDER", "收单", new BigDecimal("120.00"),
                    "ACC0001", "平台备付金", "ACC0003", "应付场地方");
            insertVoucher("VCH0002", "SETTLE", "结算出账", new BigDecimal("4200.00"),
                    "ACC0003", "应付场地方", "ACC0001", "平台备付金");
        }

        if (settlements.selectCount(null) == 0) {
            insertSettlement("STL0001", "AGENT", "AG001", "AG001 代理商", period,
                    new BigDecimal("8600.00"), "GEN");
            insertSettlement("STL0002", "VENUE", "VEN001", "Dubai Mall 运营方", period,
                    new BigDecimal("4200.00"), "CONFIRMED");
        }

        if (invoices.selectCount(null) == 0) {
            insertInvoice("INV0001", "AGENT", "AG001", "AG001 代理商", new BigDecimal("8600.00"),
                    "DRAFT", null, null, "STL0001");
            insertInvoice("INV0002", "VENUE", "VEN001", "Dubai Mall 运营方", new BigDecimal("4200.00"),
                    "ISSUED", "042", "20260800012345", "STL0002");
        }

        if (reconTasks.selectCount(null) == 0) {
            // 唯一键 uk_recon_channel_date(tenant, channel, bill_date)：同渠道一天只有一张批次
            insertRecon("RCN0001", period, 1, new BigDecimal("15600.00"), new BigDecimal("15600.00"), "MATCHED");
            insertRecon("RCN0002", period, 2, new BigDecimal("9820.00"), new BigDecimal("9760.00"), "DIFF");
        }
        // 差错行独立判空：批次先建成功、差错行失败时，整块判空会让差错永远补不上
        if (reconDiffs.selectCount(null) == 0
                && reconTasks.selectCount(new LambdaQueryWrapper<ReconTask>()
                        .eq(ReconTask::getBatchNo, "RCN0002")) > 0) {
            for (int i = 0; i < 2; i++) {
                ReconDiff d = new ReconDiff();
                d.setTenantId(TENANT);
                d.setBatchNo("RCN0002");
                d.setPayNo("PAY" + (7001 + i));
                d.setDiffType(i == 0 ? "AMOUNT_MISMATCH" : "MISSING_IN_LEDGER");
                // detail 是 JSON 列（V13，json_valid CHECK）：写 JSON 对象而非裸文本
                d.setDetail(ai.neargo.sharehub.common.Json.write(java.util.Map.of(
                        "reason", i == 0 ? "渠道 40.00 / 账务 25.00" : "渠道有单账务无分录")));
                d.setResolved(0);
                reconDiffs.insert(d);
            }
        }
    }

    private void insertShareRule(String no, String dimension, String payeeNo, String payeeName,
                                 BigDecimal rate, int priority) {
        ShareRule r = new ShareRule();
        r.setRuleNo(no);
        r.setTenantId(TENANT);
        r.setDimension(dimension);
        r.setPayeeNo(payeeNo);
        r.setPayeeName(payeeName);
        r.setMode("RATE");
        r.setRate(rate);          // [db-design §1.5] rate 是 0..1 小数，不是百分数
        r.setPriority(priority);
        r.setCurrency(CCY);
        shareRules.insert(r);
    }

    private void insertAccount(String no, String ownerType, String ownerNo, String acctType, BigDecimal balance) {
        AcctAccount a = new AcctAccount();
        a.setAccountNo(no);
        a.setTenantId(TENANT);
        a.setOwnerType(ownerType);
        a.setOwnerNo(ownerNo);
        a.setAcctType(acctType);
        a.setBalance(balance);
        a.setFrozen(BigDecimal.ZERO);
        a.setCurrency(CCY);
        accounts.insert(a);
    }

    /** 一张凭证 = 借贷两行，金额相等。 */
    private void insertVoucher(String voucherNo, String bizType, String summary, BigDecimal amount,
                               String debitAcc, String debitName, String creditAcc, String creditName) {
        insertEntry(voucherNo + "-D", voucherNo, bizType, summary, amount, debitAcc, debitName, "DEBIT");
        insertEntry(voucherNo + "-C", voucherNo, bizType, summary, amount, creditAcc, creditName, "CREDIT");
    }

    private void insertEntry(String entryNo, String voucherNo, String bizType, String summary,
                             BigDecimal amount, String accountNo, String account, String direction) {
        AcctLedger e = new AcctLedger();
        e.setTenantId(TENANT);
        e.setEntryNo(entryNo);
        e.setVoucherNo(voucherNo);
        e.setAccountNo(accountNo);
        e.setAccount(account);
        e.setDirection(direction);
        e.setAmount(amount);
        e.setCurrency(CCY);
        e.setSummary(summary);
        e.setBizType(bizType);
        e.setCreatedAt(LocalDateTime.now().toString());
        ledgers.insert(e);
    }

    private void insertSettlement(String no, String payeeType, String payeeNo, String payeeName,
                                  String period, BigDecimal total, String status) {
        StlSettlement s = new StlSettlement();
        s.setSettleNo(no);
        s.setTenantId(TENANT);
        s.setPayeeType(payeeType);
        s.setPayeeNo(payeeNo);
        s.setPayeeName(payeeName);
        s.setPeriod(period);
        s.setTotalAmount(total);
        s.setCurrency(CCY);
        s.setStatus(status);
        if ("CONFIRMED".equals(status)) {
            s.setConfirmedBy("finance");
            s.setConfirmedAt(LocalDateTime.now().minusDays(1));
        }
        settlements.insert(s);

        StlSettlementDetail d = new StlSettlementDetail();
        d.setTenantId(TENANT);
        d.setSettleNo(no);
        d.setRefType("SHARE");
        d.setRefNo("SR10000");
        d.setAmount(total);
        settlementDetails.insert(d);
    }

    private void insertInvoice(String no, String payeeType, String payeeNo, String payeeName,
                               BigDecimal amount, String status, String code, String number, String sourceNo) {
        FinInvoice e = new FinInvoice();
        e.setInvoiceNo(no);
        e.setTenantId(TENANT);
        e.setPayeeType(payeeType);
        e.setPayeeNo(payeeNo);
        e.setPayeeName(payeeName);
        e.setAmount(amount);
        e.setVatTrn("100" + payeeNo);
        e.setCurrency(CCY);
        e.setStatus(status);
        e.setSourceType("SETTLEMENT");
        e.setSourceNo(sourceNo);
        e.setInvoiceCode(code);
        e.setInvoiceNumber(number);
        if ("ISSUED".equals(status)) {
            e.setIssuedAt(LocalDateTime.now().minusDays(1).toString());
            e.setIssuedBy("finance");
        }
        invoices.insert(e);
    }

    private void insertRecon(String no, String period, int daysAgo, BigDecimal channel, BigDecimal ledger, String status) {
        ReconTask t = new ReconTask();
        t.setBatchNo(no);
        t.setTenantId(TENANT);
        t.setChannel("nearpay");
        t.setPeriod(period);
        t.setBillDate(LocalDate.now().minusDays(daysAgo).toString());
        t.setNearpayTotal(channel);
        t.setLedgerTotal(ledger);
        t.setDiff(channel.subtract(ledger));
        t.setCurrency(CCY);
        t.setStatus(status);
        if ("DIFF".equals(status)) t.setHandleStatus("OPEN");
        reconTasks.insert(t);
    }

    // ——————————————————————— 组织 ———————————————————————

    private void seedOrg() {
        if (depts.selectCount(null) == 0) {
            insertDept("D001", null, "运营中心", 1);
            insertDept("D002", "D001", "设备运维组", 2);
            insertDept("D003", "D001", "客户服务组", 3);
            insertDept("D004", null, "财务部", 4);
        }
        if (employees.selectCount(null) == 0) {
            insertEmployee("E1001", "Ali Hassan", "+971500000001", "ali@sharehub.ae", "D002", "OPS");
            insertEmployee("E1002", "Omar Khan", "+971500000002", "omar@sharehub.ae", "D002", "OPS");
            insertEmployee("E1003", "Sara Ahmed", "+971500000003", "sara@sharehub.ae", "D003", "CS");
            insertEmployee("E1004", "Fatima N.", "+971500000004", "fatima@sharehub.ae", "D004", "FINANCE");
            insertEmployee("E1005", "Wang Lei", "+971500000005", "wang@sharehub.ae", "D001", "ADMIN");
        }
    }

    private void insertDept(String no, String parent, String name, int sort) {
        IamDept d = new IamDept();
        d.setDeptNo(no);
        d.setTenantId(TENANT);
        d.setParentNo(parent);
        d.setName(name);
        d.setPath(parent == null ? no : parent + "/" + no);
        d.setSort(sort);
        d.setStatus("ACTIVE");
        depts.insert(d);
    }

    private void insertEmployee(String no, String name, String phone, String email, String deptNo, String roleNo) {
        IamEmployee e = new IamEmployee();
        e.setEmployeeNo(no);
        e.setTenantId(TENANT);
        e.setName(name);
        e.setPhone(phone);
        e.setEmail(email);
        e.setDeptNo(deptNo);
        e.setRoleNo(roleNo);
        e.setStatus("ACTIVE");
        employees.insert(e);
    }
}
