package ai.neargo.sharehub.alarm.engine;

import ai.neargo.sharehub.alarm.AlarmDomain;
import ai.neargo.sharehub.alarm.ImpactPeriod;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmSiteProfile;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.eval.Finding;
import ai.neargo.sharehub.wo.WoPriority;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 影响评估（TDD/05 §六）：同一个码，整站 · 高峰 · A 级站 · 有在途订单时更急。
 * 安全域不加成（impact_adjust=0）—— 电池隐患不因为是凌晨就不急。
 */
@Component
public class ImpactAssessor {

    public record Impact(ImpactScope scope, ImpactPeriod period, String tier, int inFlight, WoPriority priority) {
    }

    public Impact assess(Finding f, DevAlarmCode code, String openHours, DevAlarmSiteProfile profile, LocalDateTime now) {
        ImpactPeriod period = !BusinessHours.isOpen(openHours, now) ? ImpactPeriod.CLOSED
                : isPeak(profile, now) ? ImpactPeriod.PEAK : ImpactPeriod.OPEN;
        String tier = profile == null || profile.getTier() == null ? "B" : profile.getTier();   // 无画像（新站）按 B
        // 判定器按业务刻度给定的优先级（合同到期：60 / 30 / 7 天三档）优先 —— 这类告警的急迫度来自日历，不来自现场
        String forced = f.attrs() == null ? null : f.attrs().get(Finding.ATTR_PRIORITY);
        if (forced != null) return new Impact(f.scope(), period, tier, f.inFlightOrders(), WoPriority.of(forced));
        WoPriority base = WoPriority.of(code.getBasePriority() == null ? "MEDIUM" : code.getBasePriority());
        if (code.getImpactAdjust() != null && code.getImpactAdjust() == 0) {
            return new Impact(f.scope(), period, tier, f.inFlightOrders(), base);
        }
        int d = 0;
        if (f.scope() == ImpactScope.SITE) d++;
        if (period == ImpactPeriod.PEAK) d++;
        if ("A".equals(tier)) d++;
        if ("C".equals(tier) && period != ImpactPeriod.PEAK) d--;
        if (AlarmDomain.RETURNABILITY.name().equals(code.getDomain()) && f.inFlightOrders() >= 1) d++;
        return new Impact(f.scope(), period, tier, f.inFlightOrders(), base.plus(d));
    }

    private static boolean isPeak(DevAlarmSiteProfile p, LocalDateTime now) {
        if (p == null || p.getPeakHours() == null || p.getPeakHours().isBlank()) return false;
        String h = String.valueOf(now.getHour());
        return Arrays.stream(p.getPeakHours().split(",")).map(String::trim).anyMatch(h::equals);
    }
}
