package ai.neargo.sharehub.common;

/**
 * 业务键前缀注册表（[db-design §1.4.1] 的代码化，**与该表 1:1，改动需同步双方**）。
 *
 * <p>为什么要集中登记：前缀一旦分配就进了历史数据，撞了改不回来。
 * 已知的两组危险相邻前缀必须保持区分 —— {@code NBL}(触达拉黑) ≠ {@code BL}(用户黑名单)、
 * {@code ISS}(问题字典) ≠ {@code PB}(充电宝)；{@code INV}(运营侧发票) ≠ {@code UINV}(C端开票申请)。
 *
 * <p>取号规则见 {@code AbstractCrudService#nextNo}：扫描同前缀最大号 +1，
 * <b>禁止</b>「前缀 + 数组长度」。
 *
 * <p><b>自然键不在此列</b>：{@code vendor_code} / {@code channel_code} / {@code bank_code} /
 * {@code alarm_code} / {@code param_key} / {@code region_id} / {@code country_code} 等对外有语义，
 * 由调用方显式提供，不代为取号。
 */
public final class BizKey {

    private BizKey() {
    }

    // —— 设备 / 网关 ——
    public static final String CABINET = "CAB";
    public static final String POWERBANK = "PB";
    public static final String COMMAND = "CMD";
    public static final String DEVICE_LOG = "LOG";
    public static final String CODE_BATCH = "BC";
    public static final String OTA_ROLLOUT = "OTA";
    /** 固件版本（dev_ota_release）。 */
    public static final String FIRMWARE = "FW";
    public static final String TRANSFER = "TR";

    // —— 告警 / 工单 ——
    public static final String ALARM = "ALM";
    public static final String ALARM_NOTICE = "AN";
    public static final String ALARM_RULE = "AR";
    public static final String WORK_ORDER = "WO";
    public static final String SLA_RULE = "SLA";
    public static final String INSPECTION = "IP";

    // —— 场地 / 代理 ——
    public static final String SITE = "ST";
    public static final String LOCATION = "LOC";
    public static final String VENUE = "VEN";
    public static final String CONTRACT = "CT";
    public static final String LEAD = "LD";
    public static final String ONBOARDING = "OB";
    public static final String AGENT = "AG";
    public static final String AGENT_ACCOUNT = "AA";
    public static final String AGENT_COMMISSION = "AC";

    // —— 交易 ——
    public static final String RENT_ORDER = "ORD";
    /** 异常订单（db-design §1.4.1 注册表原本漏登记，2026-07-29 补）。 */
    public static final String ORDER_EXCEPTION = "OEX";
    /** 订单人工干预留痕 {@code ord_intervention.intervention_no}。**不用 IT** —— 与 {@link #INVOICE_TITLE}(ITL) 前缀过近，肉眼易混。 */
    public static final String ORDER_INTERVENTION = "ITV";
    public static final String DEPOSIT = "DEP";
    public static final String COMPLAINT = "CPL";
    public static final String REFUND = "RFD";
    public static final String RESERVATION = "RSV";
    public static final String PRICE_PLAN = "PP";
    public static final String PRICING_DIFF = "PD";
    public static final String PRICING_SCHEDULE = "PS";

    // —— 支付（db-design §1.4.1 注册表原本漏登记，2026-07-29 补；已核对不与 PB/PD/PS/RFD 相撞）——
    /** 支付引用 pay_order.pay_no。 */
    public static final String PAY_ORDER = "PAY";
    /** 免押授权 pay_auth.auth_no。 */
    public static final String PAY_AUTH = "PA";
    /** 渠道退款引用 pay_refund.refund_no —— 与业务审批单 {@link #REFUND}(RFD) 分开。 */
    public static final String PAY_REFUND = "PRF";

    // —— 财务 ——
    public static final String SHARE_RULE = "SR";
    public static final String SHARE_RECORD = "SREC";
    public static final String SETTLEMENT = "STL";
    public static final String WITHDRAWAL = "WD";
    public static final String LEDGER_ENTRY = "LE";
    public static final String VOUCHER = "V";
    public static final String RECONCILE = "RC";
    /** 运营侧开票管理。 */
    public static final String INVOICE_OPS = "INV";
    /** C端开票申请 —— 必须与 {@link #INVOICE_OPS} 分开，否则两表业务键会撞。 */
    public static final String INVOICE_USER = "UINV";

    // —— 用户 / 资产 ——
    /**
     * C 端用户。
     *
     * <p><b>钱包 / 会员 / 免费白名单不另发前缀</b>：它们是用户的 1:1 附属表，
     * 前端契约（{@code Wallet}/{@code Member}/{@code FreeUserWhitelist}）**一律以 {@code userNo} 为主键**，
     * 同一个 {@code U0001} 在这四张表里指的就是同一个人 —— 这是正确的，不是撞号。
     * db-design 另列的 {@code wallet_no}/{@code mbr_no}/{@code whitelist_no} 属内部键，不对外。
     *
     * <p>另注：{@code nextNo} 的前缀扫描**是按表进行的**，所以 {@code U} 与 {@code UINV} 这类
     * 「前缀是另一前缀的前缀」跨表并不会互相干扰；只有同一张表里混用两个此类前缀才会出问题。
     */
    public static final String C_USER = "U";
    /** 钱包流水 usr_wallet_txn.txn_no。 */
    public static final String WALLET_TXN = "WTX";
    /** 站内消息 usr_message.message_no。 */
    public static final String MESSAGE = "MSG";
    /** 充值订单 usr_recharge_order.recharge_no。 */
    public static final String RECHARGE_ORDER = "RCH";
    /** 发票抬头 usr_invoice_title.title_no。 */
    public static final String INVOICE_TITLE = "ITL";
    public static final String USER_RISK = "RK";
    /** 用户黑名单 —— 注意与 {@link #NOTIFY_BLACKLIST} 区分。 */
    public static final String USER_BLACKLIST = "BL";
    public static final String RECHARGE_PACKAGE = "RP";
    public static final String CONSUMER_SEGMENT = "SEG";

    // —— 营销 / 广告 ——
    public static final String COUPON = "CP";
    public static final String CAMPAIGN = "CMP";
    public static final String PUSH_MESSAGE = "PM";
    public static final String REFERRAL = "RF";
    /** 裂变**规则**。与 {@link #REFERRAL}（一条邀请关系）分前缀：同域重名的业务键
     *  会让「按号查」查出另一类东西来（[db-design §1.4.1] 登记过五个这样的键）。 */
    public static final String REFERRAL_RULE = "RFR";
    public static final String NOTICE = "NTC";
    public static final String AD_SLOT = "AS";
    public static final String AD_CAMPAIGN = "AD";
    public static final String AD_DELIVERY = "DLV";

    // —— 客服 ——
    public static final String CS_TICKET = "TK";
    public static final String CS_SESSION = "CS";

    // —— 平台 ——
    public static final String EMPLOYEE = "E";
    public static final String ROLE = "R";
    public static final String DEPARTMENT = "D";
    public static final String AUDIT = "A";
    public static final String TENANT = "T";
    public static final String NOTIFY_TEMPLATE = "NT";
    public static final String NOTIFY_LOG = "NL";
    /** 触达拉黑 —— 注意与 {@link #USER_BLACKLIST} 区分。 */
    public static final String NOTIFY_BLACKLIST = "NBL";
    public static final String DICT = "DC";
    /** 问题字典 —— 注意与 {@link #POWERBANK} 区分。 */
    public static final String PROBLEM = "ISS";
    public static final String OPENAPI_APP = "APP";
}
