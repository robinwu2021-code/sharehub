package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserBlacklist;
import ai.neargo.sharehub.user.core.entity.UsrBlacklist;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrBlacklistMapper;
import ai.neargo.sharehub.user.core.service.BizNoAllocator;
import ai.neargo.sharehub.user.core.service.NicknameLookup;
import ai.neargo.sharehub.user.core.service.UserBlacklistService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 黑名单实现。状态只有两态，用显式校验代替独立状态机（{@code ACTIVE → RELEASED} 单向、不可回头，
 * 再次拉黑是<b>新开一条记录</b>而不是把旧记录改回 ACTIVE —— 否则两次拉黑的原因/时间会互相覆盖）。
 */
@Service
public class UserBlacklistServiceImpl implements UserBlacklistService {

    private static final String TENANT_MAIN = "MAIN";
    private static final String ACTIVE = "ACTIVE";
    private static final String RELEASED = "RELEASED";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UsrBlacklistMapper mapper;
    private final NicknameLookup nicknames;

    public UserBlacklistServiceImpl(UsrBlacklistMapper mapper, NicknameLookup nicknames) {
        this.mapper = mapper;
        this.nicknames = nicknames;
    }

    @Override
    public PageResult<UserBlacklist> page(Integer page, Integer size, String keyword, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrBlacklist> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrBlacklist::getBlacklistNo, keyword)
                    .or().like(UsrBlacklist::getCUserNo, keyword)
                    .or().like(UsrBlacklist::getReason, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(UsrBlacklist::getStatus, status);
        w.orderByDesc(UsrBlacklist::getId);

        Page<UsrBlacklist> r = mapper.selectPage(new Page<>(p, s), w);
        Map<String, String> nick = nicknames.byUserNos(r.getRecords().stream().map(UsrBlacklist::getCUserNo).toList());
        List<UserBlacklist> rows = r.getRecords().stream().map(e -> toVO(e, nick.get(e.getCUserNo()))).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public UserBlacklist block(String cUserNo, String reason, String operatorNo) {
        UsrBlacklist active = findActive(cUserNo);
        if (active != null) {
            return toVO(active, nicknames.byUserNo(cUserNo)); // 幂等：重复拉黑不叠加记录
        }
        String now = LocalDateTime.now().format(TS);
        UsrBlacklist e = new UsrBlacklist();
        e.setBlacklistNo(nextNo());
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(cUserNo);
        e.setReason(reason);
        e.setBlacklistedAt(now);
        e.setBlacklistedBy(operatorNo);
        e.setStatus(ACTIVE);
        mapper.insert(e);
        return toVO(e, nicknames.byUserNo(cUserNo));
    }

    @Override
    public UserBlacklist release(String cUserNo, String operatorNo) {
        UsrBlacklist e = findActive(cUserNo);
        if (e == null) {
            throw new IllegalStateException("该用户当前不在黑名单中，无法解除: " + cUserNo);
        }
        e.setStatus(RELEASED);
        e.setReleasedAt(LocalDateTime.now().format(TS));
        e.setReleasedBy(operatorNo);
        mapper.updateById(e); // 只改状态与解除留痕，记录保留（不 deleteById）
        return toVO(e, nicknames.byUserNo(cUserNo));
    }

    private UsrBlacklist findActive(String cUserNo) {
        return mapper.selectOne(new LambdaQueryWrapper<UsrBlacklist>()
                .eq(UsrBlacklist::getCUserNo, cUserNo)
                .eq(UsrBlacklist::getStatus, ACTIVE)
                .orderByDesc(UsrBlacklist::getId)
                .last("limit 1"));
    }

    private String nextNo() {
        return BizNoAllocator.next(mapper, "blacklist_no", BizKey.USER_BLACKLIST, UsrBlacklist::getBlacklistNo);
    }

    private static UserBlacklist toVO(UsrBlacklist e, String nickname) {
        return new UserBlacklist(e.getBlacklistNo(), e.getCUserNo(), nickname, null,
                e.getReason(), e.getBlacklistedAt(), e.getBlacklistedBy(),
                e.getReleasedAt(), e.getReleasedBy(), e.getStatus());
    }
}
