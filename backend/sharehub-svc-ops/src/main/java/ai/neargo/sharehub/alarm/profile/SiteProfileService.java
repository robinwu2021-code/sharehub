package ai.neargo.sharehub.alarm.profile;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmSiteProfile;
import ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.SiteProfileMapper;
import ai.neargo.sharehub.api.core.port.OrderQueryPort;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteStatePort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警用站点画像（TDD/05 §六）：近 30 天 GMV 前 20% = A、后 20% = C、其余 B；订单最多的 3 个小时为高峰
 * （样本 < 30 单的站点不标高峰）。派生表，每日覆盖写，可随时重算。
 */
@Service
public class SiteProfileService {

    static final int DAYS = 30;
    static final int MIN_SAMPLES = 30;

    private final SiteStatePort sites;
    private final OrderQueryPort orders;
    private final SiteProfileMapper profiles;

    public SiteProfileService(SiteStatePort sites, OrderQueryPort orders, SiteProfileMapper profiles) {
        this.sites = sites;
        this.orders = orders;
        this.profiles = profiles;
    }

    /** @return 重算的站点数 */
    public int recompute() {
        return DataScopeContext.executeWithoutScope(() -> {
            List<SiteBrief> all = new ArrayList<>();
            long after = 0;
            while (true) {
                List<SiteBrief> batch = sites.listByStatus(List.of("ACTIVE", "PAUSED"), after, 500);
                if (batch.isEmpty()) break;
                all.addAll(batch);
                after = sites.idOf(batch.get(batch.size() - 1).siteNo());
                if (batch.size() < 500) break;
            }
            if (all.isEmpty()) return 0;
            List<String> nos = all.stream().map(SiteBrief::siteNo).toList();
            Map<String, BigDecimal> gmv = orders.gmvBySites(nos, DAYS);
            Map<String, Map<Integer, Long>> hours = orders.ordersByHour(nos, DAYS);
            List<String> ranked = nos.stream().sorted(Comparator.comparing((String s) -> gmv.getOrDefault(s, BigDecimal.ZERO)).reversed()).toList();
            int n = ranked.size(), top = Math.max(1, n / 5), bottom = n / 5;
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < n; i++) {
                String site = ranked.get(i);
                String tier = n < 5 ? "B" : i < top ? "A" : i >= n - bottom ? "C" : "B";
                Map<Integer, Long> h = hours.getOrDefault(site, Map.of());
                long samples = h.values().stream().mapToLong(Long::longValue).sum();
                String peak = samples < MIN_SAMPLES ? null : h.entrySet().stream()
                        .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()).limit(3)
                        .map(e -> String.valueOf(e.getKey())).sorted().collect(Collectors.joining(","));
                DevAlarmSiteProfile p = profiles.selectOne(new LambdaQueryWrapper<DevAlarmSiteProfile>()
                        .eq(DevAlarmSiteProfile::getSiteNo, site).last("limit 1"));
                boolean insert = p == null;
                if (insert) {
                    p = new DevAlarmSiteProfile();
                    p.setSiteNo(site);
                }
                p.setTier(tier);
                p.setPeakHours(peak);
                p.setGmv30d(gmv.getOrDefault(site, BigDecimal.ZERO));
                p.setComputedAt(now);
                if (insert) profiles.insert(p); else profiles.updateById(p);
            }
            return n;
        });
    }
}
