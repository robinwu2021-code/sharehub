package ai.neargo.sharehub.report.mapper;

import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.order.entity.OrdRefund;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.wo.entity.WoOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 报表域聚合 Mapper（集中一文件，嵌套接口，随 {@code @MapperScan} 的 markerInterface 扫描）。
 *
 * <p><b>为什么用 GROUP BY SQL 而不是 Java 内存全表聚合</b>：{@code SiteAnalysisServiceImpl}
 * 留下的 TODO 明确要求过 —— 报表是唯一会被要求「按年查」的读路径，把 {@code ord_order} 全量
 * 拉进内存再 stream 折叠，量一上来就是 OOM，且没有任何 index 能救。这里一律「一条聚合 SQL 出事实」。
 *
 * <p><b>每张接口都 extends BaseMapper&lt;X&gt;</b>：{@code MybatisPlusConfig} 的
 * {@code @MapperScan} 用 {@code markerInterface = BaseMapper.class} 过滤，不继承就**不会被扫到**，
 * 表现为启动时 NoSuchBeanDefinition（而不是编译错），排查很费时间。X 取该 SQL 的主表实体。
 *
 * <p><b>数据范围自动生效</b>：{@code DataPermissionInterceptor} 按表名（含别名）改写 SQL，
 * 自定义 {@code @Select} 与 MP 内置方法一视同仁。所以代理商登录看报表会自动只看到自己名下的站点
 * —— 不需要、也**不要**在这里手写 {@code agent_no} 条件（会与拦截器叠加成双重过滤）。
 *
 * <p><b>返回 {@code Map<String,Object>} 而不是 record</b>：record 的构造器按名映射依赖
 * {@code -parameters} 编译参数，本工程未显式声明，靠它会在运行期才炸。列别名写成 camelCase，
 * 由 service 显式取值并做数字收窄 —— 多几行代码换一个编译期就能看见的边界。
 *
 * <p><b>时间列的类型陷阱</b>：{@code ord_order.rent_start_at} 是 {@code VARCHAR(32)}，存 UTC
 * ISO-8601（{@code 2026-07-11T12:00:00Z}），不是 DATETIME（[V13__entity_column_reconcile] 改的）。
 * 故区间过滤用 {@code LEFT(...,10) BETWEEN} 而不是日期函数比较：前者是纯字符串比较，
 * 语义与「业务日」严格一致；后者依赖 MariaDB 的隐式转换，遇到脏值会静默变 NULL 而整行消失。
 */
public final class ReportMappers {

    private ReportMappers() {
    }

    /** 订单事实一行：(业务日, 小时, 站点名) → 单量 / GMV。 */
    public interface OrderFactMapper extends BaseMapper<OrdOrder> {

        /**
         * 事实层：站点 × 日 × 小时。**全域唯一的订单事实来源** ——
         * 周期报表按日折叠、大屏按小时折叠、排名按站点折叠，都是它的不同折法，
         * 所以「表格合计 = 折线合计 = 汇总条」是结构性成立的，不靠约定。
         *
         * <p>站点名三级兜底：合约主数据名 → 订单冗余名 → {@code 未归属}。
         * 不用 {@code IFNULL} 而用 {@code NULLIF+COALESCE} 是因为库里存在空串
         * （空串不是 NULL，直接 COALESCE 会得到一行名为空的报表行，rowKey 为空前端会崩）。
         *
         * @param fromDate {@code yyyy-MM-dd} 含
         * @param toDate   {@code yyyy-MM-dd} 含
         */
        @Select("""
                SELECT LEFT(o.rent_start_at, 10)                                        AS bizDate,
                       CAST(SUBSTRING(o.rent_start_at, 12, 2) AS UNSIGNED)              AS bizHour,
                       COALESCE(NULLIF(s.name, ''), NULLIF(o.location_name, ''), '未归属') AS siteName,
                       COUNT(*)                                                         AS orders,
                       COALESCE(SUM(o.fee_amount), 0)                                   AS gmv,
                       MIN(o.currency)                                                  AS currency
                  FROM ord_order o
                  LEFT JOIN loc_site s ON s.site_no = o.site_no AND s.deleted = 0
                 WHERE o.deleted = 0
                   AND o.rent_start_at IS NOT NULL
                   AND LEFT(o.rent_start_at, 10) BETWEEN #{fromDate} AND #{toDate}
                 GROUP BY bizDate, bizHour, siteName
                """)
        java.util.List<java.util.Map<String, Object>> facts(@Param("fromDate") String fromDate,
                                                            @Param("toDate") String toDate);

        /** 在借中充电宝数。{@code dev_powerbank} 无数据，故以「IN_USE 订单数」为口径 —— 一单一宝，1:1。 */
        @Select("SELECT COUNT(*) FROM ord_order WHERE deleted = 0 AND status = 'IN_USE'")
        long inUseCount();

        /** 某业务日的活跃用户（去重）。逐小时事实里的 COUNT(DISTINCT) 不可相加，故单独一条。 */
        @Select("""
                SELECT COUNT(DISTINCT c_user_no) FROM ord_order
                 WHERE deleted = 0 AND rent_start_at IS NOT NULL
                   AND LEFT(rent_start_at, 10) BETWEEN #{fromDate} AND #{toDate}
                """)
        long activeUsers(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

        /**
         * 逐用户的成功借出画像（全生命周期，不切周期 —— 人群分层是存量口径）。
         *
         * <p>只统计**真的借出去了**的订单（{@code IN_USE/RETURNED/SETTLED/CLOSED}）：
         * {@code CREATED} 是「点了没取出」，{@code EXCEPTION} 是异常单，
         * 把它们算进人群会让「成功借出」漏斗环节比它自己的下游还小。
         */
        @Select("""
                SELECT o.c_user_no                                       AS cUserNo,
                       COUNT(*)                                          AS orders,
                       COALESCE(SUM(o.fee_amount), 0)                    AS gmv,
                       MIN(o.currency)                                   AS currency,
                       MAX(LEFT(o.rent_start_at, 10))                    AS lastDate,
                       CAST(SUBSTRING(MIN(o.rent_start_at), 12, 2) AS UNSIGNED) AS firstHour
                  FROM ord_order o
                 WHERE o.deleted = 0
                   AND o.rent_start_at IS NOT NULL
                   AND o.status IN ('IN_USE', 'RETURNED', 'SETTLED', 'CLOSED')
                 GROUP BY o.c_user_no
                """)
        java.util.List<java.util.Map<String, Object>> borrowUsers();

        /** 下过单的用户总数（含未取出/异常）—— 漏斗首环节，是「成功借出」的上游。 */
        @Select("SELECT COUNT(DISTINCT c_user_no) FROM ord_order WHERE deleted = 0")
        long orderingUsers();
    }

    /** 机柜快照：站点名 → 柜数 / 在线数 / 故障数。 */
    public interface CabinetFactMapper extends BaseMapper<DevCabinet> {

        /**
         * <b>柜数取 {@code COUNT(dev_cabinet)} 而不是 {@code loc_site.cabinet_count} 列</b>
         * （[db-design §1.4]「计数列不是列，是聚合」）—— 库里那一列与实际机柜数已经不一致
         * （12 个站点标了 cabinet_count>0，但只有 7 个站点真有机柜行）。
         *
         * <p><b>在线/故障刻意互斥</b>：{@code onlineCount} 排除了 FAULT。
         * 库里存在 {@code online_status='ONLINE' AND status='FAULT'} 的柜子，
         * 不互斥的话大屏「在线 + 故障 + 离线」会大于机柜总数，饼图直接超过 100%。
         */
        @Select("""
                SELECT COALESCE(NULLIF(s.name, ''), NULLIF(c.location_name, ''), '未归属') AS siteName,
                       MIN(c.site_no)                                                  AS siteNo,
                       MIN(s.scene_type)                                               AS sceneType,
                       COUNT(*)                                                        AS cabinetCount,
                       SUM(CASE WHEN c.online_status = 'ONLINE' AND c.status <> 'FAULT'
                                THEN 1 ELSE 0 END)                                     AS onlineCount,
                       SUM(CASE WHEN c.status = 'FAULT' THEN 1 ELSE 0 END)              AS faultCount
                  FROM dev_cabinet c
                  LEFT JOIN loc_site s ON s.site_no = c.site_no AND s.deleted = 0
                 WHERE c.deleted = 0
                 GROUP BY siteName
                """)
        java.util.List<java.util.Map<String, Object>> snapshot();
    }

    /** 场地合约的分润条款：站点名 → 生效分润率。 */
    public interface ContractRateMapper extends BaseMapper<LocContract> {

        /**
         * <b>成本口径的唯一来源</b>：点位报表的 cost、财务报表的 share、自定义报表的 COST/NET
         * 都乘这一份费率。三处各写一个常量（mock 里是硬编码 0.35）就会出现
         * 「坪效的成本 ≠ 财务的分润」，对不上时无法判断哪个是对的。
         *
         * <p>{@code loc_contract.site_no} 当前全为 NULL（进件流程未回填），故只能按
         * {@code site_name} 关联 —— 与报表按名字聚合的口径正好一致，不算凑。
         * 同名多份生效合约取 {@code MAX(share_rate)}（确定性 + 成本偏保守高，不会低估支出）。
         */
        @Select("""
                SELECT c.site_name AS siteName, MAX(c.share_rate) AS shareRate
                  FROM loc_contract c
                 WHERE c.deleted = 0 AND c.status = 'ACTIVE'
                   AND c.site_name IS NOT NULL AND c.site_name <> ''
                 GROUP BY c.site_name
                """)
        java.util.List<java.util.Map<String, Object>> activeRates();
    }

    /** 已发生分润流水（{@code share_record}）按业务日聚合。 */
    public interface ShareFactMapper extends BaseMapper<ShareRecord> {

        /**
         * {@code settle} = 已结算（{@code status='DONE'}）金额。
         *
         * <p><b>当前必然全 0</b>：{@code share_record} 无 seeder、结算批处理
         * （{@code POST /internal/trade/settlements/generate}）也没在开发库跑过。
         * 这不是 bug，是「已结算」这件事真的还没发生 —— 用应付分润去顶替它就是伪造财务数据。
         *
         * <p>{@code created_at} 是真 {@code DATETIME(3)}，所以这里用 {@code DATE()} 而非字符串截取。
         *
         * <p><b>⚠️ 没有 {@code deleted = 0} 条件不是漏写</b>：{@code share_record} 表<b>实际没有
         * {@code deleted} 列</b>（V3 建表脚本未含，V13 也没补），而 {@link ShareRecord} 却继承
         * {@link ai.neargo.sharehub.common.BaseEntity} 声明了它 —— 加上条件会直接
         * {@code Unknown column 'r.deleted'}。这同时意味着**该实体上 MyBatis-Plus 的逻辑删除
         * （{@code logic-delete-field: deleted}）在 share_record 上是坏的**，任何走 BaseMapper
         * 内置方法的分润查询都会报同样的错。修法是补列（DDL），不是在这里绕过，故留注不留 TODO 在别处。
         */
        @Select("""
                SELECT DATE(r.created_at)                                                 AS bizDate,
                       COALESCE(SUM(CASE WHEN r.status = 'DONE' THEN r.amount ELSE 0 END), 0) AS settle
                  FROM share_record r
                 WHERE DATE(r.created_at) BETWEEN #{fromDate} AND #{toDate}
                 GROUP BY bizDate
                """)
        java.util.List<java.util.Map<String, Object>> settledByDay(@Param("fromDate") String fromDate,
                                                                   @Param("toDate") String toDate);
    }

    /** 退款：待审批数（工作台待办中心）。口径 = {@code RefundServiceImpl.STATUS_PENDING}。 */
    public interface RefundFactMapper extends BaseMapper<OrdRefund> {

        @Select("SELECT COUNT(*) FROM ord_refund WHERE deleted = 0 AND status = 'PENDING'")
        long pendingCount();
    }

    /** 提现：待处理数（工作台待办中心）。口径 = 状态机非终态前段 {@code APPLY/AUDIT}。 */
    public interface WithdrawFactMapper extends BaseMapper<StlWithdrawal> {

        @Select("SELECT COUNT(*) FROM stl_withdrawal WHERE deleted = 0 AND status IN ('APPLY', 'AUDIT')")
        long pendingCount();
    }

    /** 告警：最近未处理告警（工作台提醒条）。只取 {@code OPEN}，ACK 过的视为已有人跟进。 */
    public interface AlarmFactMapper extends BaseMapper<DevAlarm> {

        /**
         * 提醒条要的是<b>人能读懂的告警语义</b>，故 join 码表取 message ——
         * 只出 {@code alarm_code}（E001/E002）的话，页面上是一串谁也认不出的编号，
         * 前端也无从判断该显示「离线」还是「超时」图标。
         */
        @Select("""
                SELECT a.alarm_no AS alarmNo, a.cabinet_no AS cabinetNo, a.alarm_code AS alarmCode,
                       a.level, a.occurred_at AS occurredAt, c.message AS alarmMessage
                  FROM dev_alarm a
                  LEFT JOIN dev_alarm_code c ON c.code = a.alarm_code
                 WHERE a.deleted = 0 AND a.status = 'OPEN'
                 ORDER BY a.id DESC
                 LIMIT 10
                """)
        java.util.List<java.util.Map<String, Object>> openAlarms();
    }

    /** 工单：待处理数（大屏 KPI）。 */
    public interface WoFactMapper extends BaseMapper<WoOrder> {

        /** 待处理 = 未进终态。终态白名单写死在 SQL：新增终态时这里会漏，故 KPI 名叫「待处理」而非「未完成」。 */
        @Select("""
                SELECT COUNT(*) FROM wo_order
                 WHERE deleted = 0 AND status NOT IN ('DONE', 'CLOSED', 'CANCELED', 'CANCELLED')
                """)
        long openCount();

        /** 待派单数（工作台待办中心）：还停在初始态、没人接手的工单。 */
        @Select("SELECT COUNT(*) FROM wo_order WHERE deleted = 0 AND status = 'CREATED'")
        long pendingDispatchCount();
    }
}
