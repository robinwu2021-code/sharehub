package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.common.core.ServerException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.FreeUserWhitelist;
import ai.neargo.sharehub.user.core.entity.UsrFreeWhitelist;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrFreeWhitelistMapper;
import ai.neargo.sharehub.user.core.service.BizNoAllocator;
import ai.neargo.sharehub.user.core.service.FreeWhitelistService;
import ai.neargo.sharehub.user.core.service.NicknameLookup;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 免费白名单实现。
 *
 * <p>用途 {@code reason} 是<b>枚举不是自由文本</b>：{@code ord_order.free_reason} 回指本列，
 * 自由文本会让「免费订单」页按用途分组的统计彻底散掉。
 */
@Service
public class FreeWhitelistServiceImpl implements FreeWhitelistService {

    private static final String TENANT_MAIN = "MAIN";
    private static final String ACTIVE = "ACTIVE";
    private static final String REVOKED = "REVOKED";
    private static final String UNLIMITED = "UNLIMITED";

    /** 与 ops-web {@code WhitelistReason} 一致（[types/user.ts]）。 */
    private static final Set<String> REASONS = Set.of("INTERNAL_TEST", "VIP", "BD_DEMO", "MERCHANT_SELF");
    private static final Set<String> QUOTA_TYPES = Set.of(UNLIMITED, "TIMES", "AMOUNT");

    private final UsrFreeWhitelistMapper mapper;
    private final NicknameLookup nicknames;

    public FreeWhitelistServiceImpl(UsrFreeWhitelistMapper mapper, NicknameLookup nicknames) {
        this.mapper = mapper;
        this.nicknames = nicknames;
    }

    @Override
    public PageResult<FreeUserWhitelist> page(Integer page, Integer size, String keyword,
                                              String reason, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrFreeWhitelist> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrFreeWhitelist::getWhitelistNo, keyword)
                    .or().like(UsrFreeWhitelist::getCUserNo, keyword));
        }
        if (reason != null && !reason.isBlank()) w.eq(UsrFreeWhitelist::getReason, reason);
        if (status != null && !status.isBlank()) w.eq(UsrFreeWhitelist::getStatus, status);
        w.orderByDesc(UsrFreeWhitelist::getId);

        Page<UsrFreeWhitelist> r = mapper.selectPage(new Page<>(p, s), w);
        Map<String, String> nick = nicknames.byUserNos(
                r.getRecords().stream().map(UsrFreeWhitelist::getCUserNo).toList());
        List<FreeUserWhitelist> rows = r.getRecords().stream()
                .map(e -> toVO(e, nick.get(e.getCUserNo()))).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public FreeUserWhitelist get(String cUserNo) {
        UsrFreeWhitelist e = findLatest(cUserNo);
        return e == null ? null : toVO(e, nicknames.byUserNo(cUserNo));
    }

    @Override
    public FreeUserWhitelist save(UsrFreeWhitelist body, String operatorNo) {
        validate(body);

        UsrFreeWhitelist current = findLatest(body.getCUserNo());
        if (current == null || REVOKED.equals(current.getStatus())) {
            // 撤销过的记录不复活（那条是历史凭证），重新授予开新记录
            body.setWhitelistNo(nextNo());
            body.setTenantId(TENANT_MAIN);
            body.setStatus(ACTIVE);
            body.setUsedValue(BigDecimal.ZERO);
            body.setGrantedBy(operatorNo);
            mapper.insert(body);
            return toVO(body, nicknames.byUserNo(body.getCUserNo()));
        }

        body.setId(current.getId());
        body.setVersion(current.getVersion());
        body.setWhitelistNo(current.getWhitelistNo());
        body.setTenantId(current.getTenantId());
        body.setStatus(current.getStatus());
        body.setUsedValue(current.getUsedValue()); // 已用量是系统累计的，不接受前端改
        body.setGrantedBy(current.getGrantedBy()); // 授予人是首次授予时的审计事实，不随修改漂移
        mapper.updateById(body);
        return toVO(body, nicknames.byUserNo(body.getCUserNo()));
    }

    @Override
    public FreeUserWhitelist revoke(String cUserNo, String operatorNo) {
        UsrFreeWhitelist e = findLatest(cUserNo);
        if (e == null || !ACTIVE.equals(e.getStatus())) {
            throw ServerException.of(ErrorCode.CONFLICT, "该用户没有生效中的免费白名单，无法撤销: " + cUserNo);
        }
        e.setStatus(REVOKED);
        mapper.updateById(e); // 软删除：改状态、留记录，不 deleteById
        // TODO(审计)：撤销人 operatorNo 目前无落点列（DDL 只有 granted_by）——
        //  待 audit_log 接入后以「操作审计」形态记录，或由 DDL 批次补 revoked_by/revoked_at。
        return toVO(e, nicknames.byUserNo(cUserNo));
    }

    private void validate(UsrFreeWhitelist e) {
        if (e.getCUserNo() == null || e.getCUserNo().isBlank()) {
            throw new IllegalArgumentException("cUserNo 必填");
        }
        if (!REASONS.contains(e.getReason())) {
            throw new IllegalArgumentException("reason 必须是 " + REASONS + "，收到: " + e.getReason());
        }
        String qt = e.getQuotaType() == null ? UNLIMITED : e.getQuotaType();
        if (!QUOTA_TYPES.contains(qt)) {
            throw new IllegalArgumentException("quotaType 必须是 " + QUOTA_TYPES + "，收到: " + qt);
        }
        e.setQuotaType(qt);
        if (UNLIMITED.equals(qt)) {
            e.setQuotaValue(BigDecimal.ZERO); // 无限额度不留残值，防报表误读为上限
        } else if (e.getQuotaValue() == null || e.getQuotaValue().signum() <= 0) {
            throw new IllegalArgumentException("quotaType=" + qt + " 时 quotaValue 必须大于 0");
        }
    }

    private UsrFreeWhitelist findLatest(String cUserNo) {
        if (cUserNo == null || cUserNo.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<UsrFreeWhitelist>()
                .eq(UsrFreeWhitelist::getCUserNo, cUserNo)
                .orderByDesc(UsrFreeWhitelist::getId)
                .last("limit 1"));
    }

    /** 前缀 {@code U}（[db-design §1.4.1]：U 覆盖 C端用户/会员/钱包/白名单）。 */
    private String nextNo() {
        return BizNoAllocator.next(mapper, "whitelist_no", BizKey.C_USER, UsrFreeWhitelist::getWhitelistNo);
    }

    private static FreeUserWhitelist toVO(UsrFreeWhitelist e, String nickname) {
        return new FreeUserWhitelist(e.getWhitelistNo(), e.getCUserNo(), nickname, null,
                e.getReason(), e.getQuotaType(), e.getQuotaValue(), e.getUsedValue(), e.getCurrency(),
                e.getValidFrom(), e.getValidTo(), e.getGrantedBy(), e.getStatus());
    }
}
