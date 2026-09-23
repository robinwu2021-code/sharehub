package ai.neargo.sharehub.operation.mapper;

import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 运营管理域的跨域聚合 Mapper。约定全部沿用 {@code ReportMappers}，此处只记与那边不同的取舍：
 *
 * <p><b>为什么订单要按机柜连到站点</b>：`ord_order` 上虽有 `site_no` 锚点列（V9），
 * 但它只在数据范围过滤时被回填，历史单大量为空。按 `cabinet_no → dev_cabinet.site_no`
 * 现连才是可信的 —— 站点归属的唯一真值在机柜上。
 *
 * <p><b>时间列陷阱同 ReportMappers</b>：`rent_start_at` / `rent_end_at` 是 VARCHAR(32) 存 UTC ISO，
 * 区间过滤一律 `LEFT(...,10) BETWEEN` 的纯字符串比较，不用日期函数（脏值会静默变 NULL 整行消失）。
 *
 * <p><b>「可计费」的口径与前端 mock 一致</b>：`rent_end_at` 非空且金额 > 0。
 * 免单与在途单不计入经营指标 —— 把它们算进 GMV，客单价会被摊薄成一个谁也解释不了的数。
 */
public final class OperationMappers {

    private OperationMappers() {
    }

    public interface SiteScaleMapper extends BaseMapper<LocSite> {

        /** 站点规模：总数 / 营业中 / 暂停 / 有经纬度。一条 SQL 出四个数，不要四次往返。 */
        @Select("""
                SELECT COUNT(*)                                                          AS total,
                       SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END)                AS active,
                       SUM(CASE WHEN status = 'PAUSED' THEN 1 ELSE 0 END)                AS paused,
                       SUM(CASE WHEN lng IS NOT NULL AND lat IS NOT NULL THEN 1 ELSE 0 END) AS withGeo
                  FROM loc_site
                 WHERE deleted = 0 AND archived_at IS NULL
                """)
        Map<String, Object> scale();

        /** 场景分布（站点数）。GMV 由 service 用订单侧的结果补齐 —— 两者的时间口径不同。 */
        @Select("""
                SELECT COALESCE(scene_type, '未分类') AS sceneType, COUNT(*) AS siteCount
                  FROM loc_site
                 WHERE deleted = 0 AND archived_at IS NULL
                 GROUP BY COALESCE(scene_type, '未分类')
                 ORDER BY siteCount DESC
                """)
        List<Map<String, Object>> scenes();

        /** 站点清单（待关注判定要逐站看，所以取回来，但只取判定用得上的列）。 */
        @Select("""
                SELECT site_no AS siteNo, name, venue_name AS venueName, status,
                       scene_type AS sceneType
                  FROM loc_site
                 WHERE deleted = 0 AND archived_at IS NULL
                 ORDER BY site_no
                """)
        List<Map<String, Object>> sites();
    }

    public interface CabinetStatMapper extends BaseMapper<DevCabinet> {

        /**
         * 按站点统计机柜数与在线数。
         *
         * <p><b>在线的口径</b>：`online_status='ONLINE'` 且 `status<>'FAULT'` ——
         * 与经营看板一致。故障柜虽然连着网，但它做不了生意，算进在线率会让这个数字失去意义。
         */
        @Select("""
                SELECT site_no AS siteNo, COUNT(*) AS total,
                       SUM(CASE WHEN online_status = 'ONLINE' AND status <> 'FAULT' THEN 1 ELSE 0 END) AS online,
                       MAX(last_heartbeat_at) AS lastHeartbeatAt
                  FROM dev_cabinet
                 WHERE deleted = 0 AND archived_at IS NULL AND site_no IS NOT NULL
                 GROUP BY site_no
                """)
        List<Map<String, Object>> bySite();

        /** 全量机柜规模（不分站点，含未上架的）。 */
        @Select("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN online_status = 'ONLINE' AND status <> 'FAULT' THEN 1 ELSE 0 END) AS online
                  FROM dev_cabinet
                 WHERE deleted = 0 AND archived_at IS NULL
                """)
        Map<String, Object> scale();

        /** 某站点下按点位统计机柜数（单站统计的「按点位拆分」）。 */
        @Select("""
                SELECT location_no AS locationNo, MAX(location_name) AS locationName, COUNT(*) AS cabinetCount
                  FROM dev_cabinet
                 WHERE deleted = 0 AND archived_at IS NULL AND site_no = #{siteNo}
                 GROUP BY location_no
                """)
        List<Map<String, Object>> byPoint(@Param("siteNo") String siteNo);
    }

    public interface OrderStatMapper extends BaseMapper<OrdOrder> {

        /** 区间内按站点的订单数与 GMV。站点由机柜现连（见类注释）。 */
        @Select("""
                SELECT c.site_no AS siteNo, COUNT(*) AS orders, COALESCE(SUM(o.amount), 0) AS gmv
                  FROM ord_order o
                  JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no AND c.deleted = 0
                 WHERE o.deleted = 0 AND o.rent_end_at IS NOT NULL AND o.amount > 0
                   AND LEFT(o.rent_end_at, 10) BETWEEN #{fromDate} AND #{toDate}
                 GROUP BY c.site_no
                """)
        List<Map<String, Object>> bySite(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

        /** 区间内逐日趋势（全局）。 */
        @Select("""
                SELECT LEFT(o.rent_end_at, 10) AS day, COUNT(*) AS orders, COALESCE(SUM(o.amount), 0) AS gmv
                  FROM ord_order o
                 WHERE o.deleted = 0 AND o.rent_end_at IS NOT NULL AND o.amount > 0
                   AND LEFT(o.rent_end_at, 10) BETWEEN #{fromDate} AND #{toDate}
                 GROUP BY LEFT(o.rent_end_at, 10)
                 ORDER BY day
                """)
        List<Map<String, Object>> trend(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

        /** 某站点区间内逐日趋势。 */
        @Select("""
                SELECT LEFT(o.rent_end_at, 10) AS day, COUNT(*) AS orders, COALESCE(SUM(o.amount), 0) AS gmv
                  FROM ord_order o
                  JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no AND c.deleted = 0
                 WHERE o.deleted = 0 AND o.rent_end_at IS NOT NULL AND o.amount > 0
                   AND c.site_no = #{siteNo}
                   AND LEFT(o.rent_end_at, 10) BETWEEN #{fromDate} AND #{toDate}
                 GROUP BY LEFT(o.rent_end_at, 10)
                 ORDER BY day
                """)
        List<Map<String, Object>> trendOfSite(@Param("siteNo") String siteNo,
                                              @Param("fromDate") String fromDate,
                                              @Param("toDate") String toDate);

        /** 某站点区间内按点位的订单数与 GMV，外加平均时长（单站统计用）。 */
        @Select("""
                SELECT c.location_no AS locationNo, COUNT(*) AS orders,
                       COALESCE(SUM(o.amount), 0) AS gmv
                  FROM ord_order o
                  JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no AND c.deleted = 0
                 WHERE o.deleted = 0 AND o.rent_end_at IS NOT NULL AND o.amount > 0
                   AND c.site_no = #{siteNo}
                   AND LEFT(o.rent_end_at, 10) BETWEEN #{fromDate} AND #{toDate}
                 GROUP BY c.location_no
                """)
        List<Map<String, Object>> byPointOfSite(@Param("siteNo") String siteNo,
                                                @Param("fromDate") String fromDate,
                                                @Param("toDate") String toDate);

        /** 某站点区间内的汇总（订单数 / GMV / 平均时长）。 */
        @Select("""
                SELECT COUNT(*) AS orders, COALESCE(SUM(o.amount), 0) AS gmv,
                       COALESCE(AVG(o.duration_min), 0) AS avgDurationMin
                  FROM ord_order o
                  JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no AND c.deleted = 0
                 WHERE o.deleted = 0 AND o.rent_end_at IS NOT NULL AND o.amount > 0
                   AND c.site_no = #{siteNo}
                   AND LEFT(o.rent_end_at, 10) BETWEEN #{fromDate} AND #{toDate}
                """)
        Map<String, Object> summaryOfSite(@Param("siteNo") String siteNo,
                                          @Param("fromDate") String fromDate,
                                          @Param("toDate") String toDate);

        /** 区间内全局汇总。 */
        @Select("""
                SELECT COUNT(*) AS orders, COALESCE(SUM(o.amount), 0) AS gmv
                  FROM ord_order o
                 WHERE o.deleted = 0 AND o.rent_end_at IS NOT NULL AND o.amount > 0
                   AND LEFT(o.rent_end_at, 10) BETWEEN #{fromDate} AND #{toDate}
                """)
        Map<String, Object> summary(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

        /** 近 N 天有过订单的站点（待关注 NO_ORDER 判定用）。 */
        @Select("""
                SELECT DISTINCT c.site_no AS siteNo
                  FROM ord_order o
                  JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no AND c.deleted = 0
                 WHERE o.deleted = 0 AND o.rent_end_at IS NOT NULL AND o.amount > 0
                   AND LEFT(o.rent_end_at, 10) >= #{sinceDate}
                """)
        List<String> siteNosWithOrdersSince(@Param("sinceDate") String sinceDate);
    }
}
