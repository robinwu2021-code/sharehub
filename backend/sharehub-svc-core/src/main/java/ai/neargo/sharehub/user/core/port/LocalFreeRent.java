package ai.neargo.sharehub.user.core.port;

import ai.neargo.sharehub.api.core.port.FreeRentPort;
import ai.neargo.sharehub.user.core.entity.UsrFreeWhitelist;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrFreeWhitelistMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** {@link FreeRentPort} 的本地实现（单体内同进程）。 */
@Component
public class LocalFreeRent implements FreeRentPort {

    private static final String ACTIVE = "ACTIVE";
    private static final String UNLIMITED = "UNLIMITED";
    private static final String TIMES = "TIMES";
    private static final String AMOUNT = "AMOUNT";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalFreeRent.class);

    private final UsrFreeWhitelistMapper mapper;

    public LocalFreeRent(UsrFreeWhitelistMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public FreeGrant grantFor(String cUserNo, String currency) {
        UsrFreeWhitelist w = effective(cUserNo);
        if (w == null) return null;

        BigDecimal quota = nz(w.getQuotaValue());
        BigDecimal used = nz(w.getUsedValue());
        if (TIMES.equals(w.getQuotaType())) {
            return used.compareTo(quota) < 0 ? new FreeGrant(w.getReason(), null) : null;
        }
        if (AMOUNT.equals(w.getQuotaType())) {
            // 金额额度带币种（[db-design §1.5]）。币种不符时按没有额度处理 ——
            // 拿 AED 的额度去免 SAR 的单，数字对得上、钱对不上
            if (currency != null && w.getCurrency() != null && !currency.equals(w.getCurrency())) return null;
            BigDecimal left = quota.subtract(used);
            return left.signum() > 0 ? new FreeGrant(w.getReason(), left) : null;
        }
        if (UNLIMITED.equals(w.getQuotaType())) return new FreeGrant(w.getReason(), null);

        // 额度类型是枚举，出现第四个值说明数据脏了。**按不免单处理**：
        // 猜错的方向应该是照常收费（用户来问，我们能查），而不是白送（没人会来问）
        log.warn("白名单额度类型不认识，本单按正常收费 cUserNo={} quotaType={}", cUserNo, w.getQuotaType());
        return null;
    }

    @Override
    public boolean consume(String cUserNo, String orderNo, BigDecimal waived) {
        UsrFreeWhitelist w = effective(cUserNo);
        if (w == null) return false;
        if (UNLIMITED.equals(w.getQuotaType())) return true;   // 不限量，没有额度可扣

        BigDecimal quota = nz(w.getQuotaValue());
        BigDecimal step = TIMES.equals(w.getQuotaType()) ? BigDecimal.ONE : nz(waived);
        if (step.signum() <= 0) return true;                   // 免了 0 块，不该消耗额度

        /*
         * 条件更新即并发裁决：used_value + step <= quota_value 才改得动。
         * 拿实体 update 做不到「基于当前值累加」，所以这里手写 SQL 片段。
         */
        int n = mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UsrFreeWhitelist>()
                .eq(UsrFreeWhitelist::getWhitelistNo, w.getWhitelistNo())
                .eq(UsrFreeWhitelist::getStatus, ACTIVE)
                .apply("used_value + {0} <= quota_value", step)
                // setSql 不支持占位符，只能拼串。这里拼进去的是 BigDecimal 的
                // toPlainString()，恒为数字字面量，与外部输入无关
                .setSql("used_value = used_value + " + step.toPlainString()));
        if (n != 1) {
            log.warn("免单额度已用尽，本单按原价结算 cUserNo={} orderNo={} 拟扣={}", cUserNo, orderNo, step);
            return false;
        }
        return true;
    }

    /** 当前生效的那条：ACTIVE 且今天落在 [validFrom, validTo] 内。 */
    private UsrFreeWhitelist effective(String cUserNo) {
        if (cUserNo == null || cUserNo.isBlank()) return null;
        UsrFreeWhitelist w = mapper.selectOne(new LambdaQueryWrapper<UsrFreeWhitelist>()
                .eq(UsrFreeWhitelist::getCUserNo, cUserNo)
                .eq(UsrFreeWhitelist::getStatus, ACTIVE)
                .orderByDesc(UsrFreeWhitelist::getId).last("limit 1"));
        if (w == null) return null;
        LocalDate today = LocalDate.now();
        LocalDate from = date(w.getValidFrom());
        LocalDate to = date(w.getValidTo());
        if (from != null && today.isBefore(from)) return null;   // 还没开始
        if (to != null && to.isBefore(today)) return null;       // 已经过期
        return w;
    }

    /**
     * 日期列是 DATE，映射成 String。
     *
     * <p>解析不了按 <b>null（不限）</b>：脏的有效期不该表现为「免单凭空失效」——
     * 那是运营看得见、又说不出所以然的差异。只记一条 WARN 等人来修。
     */
    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
            log.warn("白名单有效期字面量无法解析，按不限处理 value={}", s);
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
