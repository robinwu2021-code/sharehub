package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.CUserRow;
import ai.neargo.sharehub.user.core.entity.UsrBlacklist;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrBlacklistMapper;
import ai.neargo.sharehub.user.core.service.UserQueryService;
import ai.neargo.sharehub.user.entity.UsrIdentity;
import ai.neargo.sharehub.user.entity.UsrUser;
import ai.neargo.sharehub.user.mapper.UserMappers.UsrIdentityMapper;
import ai.neargo.sharehub.user.mapper.UserMappers.UsrUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户档案读模型实现。批量回填手机号（掩码）与拉黑态，避免 N+1。 */
@Service
public class UserQueryServiceImpl implements UserQueryService {

    private static final String PROVIDER_PHONE = "PHONE";
    private static final String BLACKLIST_ACTIVE = "ACTIVE";

    private final UsrUserMapper users;
    private final UsrIdentityMapper identities;
    private final UsrBlacklistMapper blacklists;

    public UserQueryServiceImpl(UsrUserMapper users, UsrIdentityMapper identities,
                                UsrBlacklistMapper blacklists) {
        this.users = users;
        this.identities = identities;
        this.blacklists = blacklists;
    }

    @Override
    public PageResult<CUserRow> page(Integer page, Integer size, String keyword) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        LambdaQueryWrapper<UsrUser> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrUser::getCUserNo, keyword).or().like(UsrUser::getNickname, keyword));
        }
        w.orderByDesc(UsrUser::getId);
        Page<UsrUser> r = users.selectPage(new Page<>(p, s), w);
        return new PageResult<>(rows(r.getRecords()), r.getTotal());
    }

    @Override
    public CUserRow get(String cUserNo) {
        UsrUser u = users.selectOne(new LambdaQueryWrapper<UsrUser>()
                .eq(UsrUser::getCUserNo, cUserNo).last("limit 1"));
        if (u == null) return null;
        return rows(List.of(u)).get(0);
    }

    @Override
    public CUserRow updateProfile(String cUserNo, String nickname, String avatar) {
        UsrUser u = users.selectOne(new LambdaQueryWrapper<UsrUser>()
                .eq(UsrUser::getCUserNo, cUserNo).last("limit 1"));
        if (u == null) throw new IllegalArgumentException("用户不存在: " + cUserNo);
        if (nickname != null && !nickname.isBlank()) u.setNickname(nickname.trim());
        if (avatar != null && !avatar.isBlank()) u.setAvatar(avatar.trim());
        users.updateById(u);
        return rows(List.of(u)).get(0);
    }

    private List<CUserRow> rows(List<UsrUser> list) {
        if (list.isEmpty()) return List.of();
        Set<String> nos = list.stream().map(UsrUser::getCUserNo).collect(Collectors.toSet());
        Map<String, String> phones = phonesOf(nos);
        Set<String> black = activeBlacklist(nos);
        return list.stream().map(u -> new CUserRow(
                u.getCUserNo(), u.getNickname(), u.getAvatar(), phones.getOrDefault(u.getCUserNo(), ""),
                u.getCreditScore(), black.contains(u.getCUserNo()), 0L,
                u.getCreatedAt() == null ? null : u.getCreatedAt().toString())).toList();
    }

    private Map<String, String> phonesOf(Collection<String> userNos) {
        return identities.selectList(new LambdaQueryWrapper<UsrIdentity>()
                        .in(UsrIdentity::getCUserNo, userNos)
                        .eq(UsrIdentity::getProvider, PROVIDER_PHONE)).stream()
                .collect(Collectors.toMap(UsrIdentity::getCUserNo,
                        i -> mask(i.getProviderUid()), (a, b) -> a));
    }

    private Set<String> activeBlacklist(Collection<String> userNos) {
        return blacklists.selectList(new LambdaQueryWrapper<UsrBlacklist>()
                        .in(UsrBlacklist::getCUserNo, userNos)
                        .eq(UsrBlacklist::getStatus, BLACKLIST_ACTIVE)).stream()
                .map(UsrBlacklist::getCUserNo).collect(Collectors.toSet());
    }

    /** 手机号掩码：保头 3 尾 4；不足 8 位整体打码 —— 出参即脱敏，明文只在 pb_pii。 */
    private static String mask(String phone) {
        if (phone == null || phone.isBlank()) return "";
        String d = phone.trim();
        if (d.length() < 8) return "****";
        return d.substring(0, 3) + "****" + d.substring(d.length() - 4);
    }
}
