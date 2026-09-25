package ai.neargo.sharehub.alarm.dispose;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.alarm.AlarmDomain;
import ai.neargo.sharehub.alarm.AlarmNoticeStatus;
import ai.neargo.sharehub.alarm.AlarmRuleStatus;
import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.alarm.entity.DevAlarmNotice;
import ai.neargo.sharehub.alarm.entity.DevAlarmRule;
import ai.neargo.sharehub.alarm.mapper.DevAlarmNoticeMapper;
import ai.neargo.sharehub.alarm.mapper.DevAlarmRuleMapper;
import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.dto.EmployeeBrief;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort;
import ai.neargo.sharehub.api.platform.port.NotifyPort;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.common.BizKey;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务告警通知（TDD/05 §八）：按 {@code dev_alarm_rule} 匹配码 → 解析接收人 → 经 {@link NotifyPort} 推送 → 落 {@code dev_alarm_notice}。
 *
 * <p><b>接收人写法</b>（规则的 target 列）：{@code SITE_OWNER} = 站点运维责任人（运营代理优先，暂停则落到员工）；
 * 大写角色码（OPS / BD / CS …）= 该角色的在职员工；其它 = 具体员工号 / 代理号。
 *
 * <p><b>安全域不静默</b>：电池隐患不因为是凌晨就不叫人。其它域落在静默窗口内的本次不发（升级时会再发）。
 *
 * <p><b>同一事件对同一接收人只发一次</b>：幂等键 = 告警号:事件:规则:接收人，复发 / 重跑不会重复打扰。
 * 通道：PUSH（站内推送）、SMS / EMAIL（按员工联系方式，经通知服务落脱敏流水；批次 E4）；
 * 发不出去的（非员工、无联系方式、仅掩码、WEBHOOK 等未接通道）落一条 FAILED 流水写明原因，而不是静默吞掉。
 */
@Component
public class AlarmNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlarmNotifier.class);
    public static final String SITE_OWNER = "SITE_OWNER";

    private final DevAlarmRuleMapper rules;
    private final DevAlarmNoticeMapper notices;
    private final NotifyPort notify;
    private final SiteQueryPort sites;
    private final AgentDirectoryPort agents;
    private final EmployeeDirectoryPort employees;

    public AlarmNotifier(DevAlarmRuleMapper rules, DevAlarmNoticeMapper notices, NotifyPort notify, SiteQueryPort sites,
                         AgentDirectoryPort agents, EmployeeDirectoryPort employees) {
        this.rules = rules;
        this.notices = notices;
        this.notify = notify;
        this.sites = sites;
        this.agents = agents;
        this.employees = employees;
    }

    /**
     * @param event OPENED 成立 / IMPACT_UP 影响升级 / ESCALATED 超时未处置升级
     * @return 本次实际发出的条数
     */
    public int notify(DevAlarm a, String event, String codeName, LocalDateTime now) {
        int sent = 0;
        boolean safety = AlarmDomain.SAFETY.name().equals(a.getDomain());
        for (DevAlarmRule r : rulesOf(a.getAlarmCode())) {
            if (!safety && !"ESCALATED".equals(event) && inQuiet(r, now.toLocalTime())) continue;
            Set<String> to = "ESCALATED".equals(event) ? escalationTargets() : recipients(r.getTarget(), a.getSiteNo());
            if (to.isEmpty()) {
                log.warn("告警通知无接收人 alarmNo={} rule={} target={} —— 请检查规则或为站点补运维责任人",
                        a.getAlarmNo(), r.getRuleNo(), r.getTarget());
                continue;
            }
            for (String target : to) {
                String key = keyOf(a.getAlarmNo(), event + ":" + r.getRuleNo() + ":" + target);
                if (notices.selectCount(new LambdaQueryWrapper<DevAlarmNotice>().eq(DevAlarmNotice::getIdempotencyKey, key)) > 0) continue;
                var d = notify.send(target, r.getChannel(), "ALARM_" + event, content(a, event, codeName));
                record(a.getAlarmNo(), r.getChannel(), target, key, d.sent(), d.reason(), now);
                if (d.sent()) sent++;
            }
        }
        return sent;
    }

    /** 超时未处置的升级：有规则配了 escalate_minutes 且告警仍停在 OPEN 超过该时长的，由调用方逐条调 notify(..., "ESCALATED")。 */
    public Integer escalateAfterMinutes(String alarmCode) {
        return rulesOf(alarmCode).stream().map(DevAlarmRule::getEscalateMinutes).filter(m -> m != null && m > 0)
                .min(Integer::compareTo).orElse(null);
    }

    private List<DevAlarmRule> rulesOf(String code) {
        return rules.selectList(new LambdaQueryWrapper<DevAlarmRule>()
                .eq(DevAlarmRule::getStatus, AlarmRuleStatus.ACTIVE.name()).isNull(DevAlarmRule::getArchivedAt)
                .and(w -> w.eq(DevAlarmRule::getAlarmCode, code).or().isNull(DevAlarmRule::getAlarmCode).or().eq(DevAlarmRule::getAlarmCode, "")));
    }

    private Set<String> recipients(String target, String siteNo) {
        Set<String> out = new LinkedHashSet<>();
        if (target == null || target.isBlank()) return out;
        for (String t : target.split("[,，]")) {
            String x = t.trim();
            if (x.isEmpty()) continue;
            if (SITE_OWNER.equals(x)) {
                String owner = siteOwner(siteNo);
                if (owner != null) out.add(owner);
            } else if (x.matches("[A-Z_]{2,32}")) {
                employees.activeByRoles(Set.of(x), 20).stream().map(EmployeeBrief::employeeNo).forEach(out::add);
            } else {
                out.add(x);
            }
        }
        return out;
    }

    private String siteOwner(String siteNo) {
        if (siteNo == null) return null;
        SiteBrief s = sites.briefsByNos(List.of(siteNo)).stream().findFirst().orElse(null);
        if (s == null) return null;
        if (s.operateAgentNo() != null) {
            AgentBrief ag = agents.briefOf(s.operateAgentNo());
            if (ag != null && ag.enabled()) return s.operateAgentNo();
        }
        if (s.opsEmployeeNo() != null) {
            EmployeeBrief e = employees.briefsOf(List.of(s.opsEmployeeNo())).get(s.opsEmployeeNo());
            if (e != null && e.active()) return s.opsEmployeeNo();
        }
        return null;
    }

    /** 升级对象：运维主管（OPS 角色在职员工）。 */
    private Set<String> escalationTargets() {
        Set<String> out = new LinkedHashSet<>();
        employees.activeByRoles(Set.of("OPS"), 20).stream().map(EmployeeBrief::employeeNo).forEach(out::add);
        return out;
    }

    static boolean inQuiet(DevAlarmRule r, LocalTime t) {
        if (r.getQuietStart() == null || r.getQuietEnd() == null || r.getQuietStart().isBlank() || r.getQuietEnd().isBlank()) return false;
        try {
            LocalTime s = LocalTime.parse(r.getQuietStart().trim()), e = LocalTime.parse(r.getQuietEnd().trim());
            return s.isBefore(e) ? !t.isBefore(s) && t.isBefore(e) : !t.isBefore(s) || t.isBefore(e);   // 跨零点
        } catch (RuntimeException ex) {
            return false;   // 配错了宁可发
        }
    }

    private static String content(DevAlarm a, String event, String codeName) {
        List<String> parts = new ArrayList<>();
        parts.add(switch (event) {
            case "IMPACT_UP" -> "【告警升级】";
            case "ESCALATED" -> "【超时未处置】";
            default -> "【告警】";
        });
        parts.add(codeName == null ? a.getAlarmCode() : codeName);
        if (a.getSiteNo() != null) parts.add("站点 " + a.getSiteNo());
        if (a.getCabinetNo() != null) parts.add("机柜 " + a.getCabinetNo());
        if (a.getCause() != null) parts.add("根因 " + a.getCause());
        if (a.getPriority() != null) parts.add("优先级 " + a.getPriority());
        parts.add("告警号 " + a.getAlarmNo());
        return String.join(" · ", parts);
    }

    /** 幂等键 = 告警号 + 事件/规则/接收人的短哈希（列只有 64 位，完整拼接会超长）。 */
    static String keyOf(String alarmNo, String rest) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(rest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return alarmNo + ":" + java.util.HexFormat.of().formatHex(h).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.security.ProviderException("SHA-256 不可用", e);
        }
    }

    private void record(String alarmNo, String channel, String target, String key, boolean sent, String reason, LocalDateTime now) {
        DevAlarmNotice n = new DevAlarmNotice();
        n.setNoticeNo(IdGenerator.next(BizKey.ALARM_NOTICE));
        n.setTenantId("MAIN");
        n.setAlarmNo(alarmNo);
        n.setChannel(channel);
        n.setTarget(target);
        n.setSentAt(now.toString());
        n.setStatus(sent ? AlarmNoticeStatus.SENT.name() : AlarmNoticeStatus.FAILED.name());
        if (!sent) n.setFailReason("通道 " + channel + " 未送达：" + reason);
        n.setIdempotencyKey(key);
        notices.insert(n);
    }
}
