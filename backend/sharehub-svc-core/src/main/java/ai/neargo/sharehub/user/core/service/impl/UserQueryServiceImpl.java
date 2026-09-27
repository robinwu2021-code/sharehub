package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.sharehub.common.BizException;
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

    private static final String BLACKLIST_ACTIVE = "ACTIVE";

    private final UsrUserMapper users;
    private final UsrBlacklistMapper blacklists;
    /** 只读 display_mask（V131）—— <b>不读 provider_uid</b>，那是哈希，见 {@link #phoneMasks}。 */
    private final UsrIdentityMapper identities;

    public UserQueryServiceImpl(UsrUserMapper users, UsrBlacklistMapper blacklists, UsrIdentityMapper identities) {
        this.users = users;
        this.blacklists = blacklists;
        this.identities = identities;
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
        if (u == null) throw BizException.notFound(cUserNo);
        if (nickname != null && !nickname.isBlank()) u.setNickname(nickname.trim());
        if (avatar != null && !avatar.isBlank()) u.setAvatar(avatar.trim());
        users.updateById(u);
        return rows(List.of(u)).get(0);
    }

    /**
     * 手机号掩码：读 {@code usr_identity.display_mask}（登录时由真号算出，V131）。
     * 老用户还没再登录过就没有，出空串。
     *
     * <h3>它此前是什么</h3>
     * 一开始是 {@code mask(identity.providerUid)} —— 把**登录标识的哈希**（HMAC 十六进制）当手机号掩码，
     * C 端「我的资料」显示 {@code 056****7c12}，运营端用户 / 风控 / 黑名单 / 白名单四张表的「手机」列
     * 全是十六进制尾巴。掩码把它伪装得很像真号 —— 这正是它一直没被发现的原因。
     * 之后改成如实出空，直到 V131 在登录时存下真号的掩码。
     *
     * <p><b>绝不回退到 provider_uid</b>：没有掩码就出空，空着能看出缺了什么，伪装成手机号看不出。
     * （{@code ConsumerProfilePhoneTest} 钉住：掩码两头只能是数字。）
     */
    private Map<String, String> phoneMasks(Collection<String> userNos) {
        return identities.selectList(new LambdaQueryWrapper<UsrIdentity>()
                        .in(UsrIdentity::getCUserNo, userNos)
                        .eq(UsrIdentity::getProvider, "PHONE")
                        .isNotNull(UsrIdentity::getDisplayMask)).stream()
                .collect(Collectors.toMap(UsrIdentity::getCUserNo, UsrIdentity::getDisplayMask, (a, b) -> a));
    }

    private List<CUserRow> rows(List<UsrUser> list) {
        if (list.isEmpty()) return List.of();
        Set<String> nos = list.stream().map(UsrUser::getCUserNo).collect(Collectors.toSet());
        Set<String> black = activeBlacklist(nos);
        Map<String, String> masks = phoneMasks(nos);
        return list.stream().map(u -> new CUserRow(
                u.getCUserNo(), u.getNickname(), u.getAvatar(), masks.getOrDefault(u.getCUserNo(), ""),
                u.getCreditScore(), black.contains(u.getCUserNo()), 0L,
                u.getCreatedAt() == null ? null : u.getCreatedAt().toString())).toList();
    }

    private Set<String> activeBlacklist(Collection<String> userNos) {
        return blacklists.selectList(new LambdaQueryWrapper<UsrBlacklist>()
                        .in(UsrBlacklist::getCUserNo, userNos)
                        .eq(UsrBlacklist::getStatus, BLACKLIST_ACTIVE)).stream()
                .map(UsrBlacklist::getCUserNo).collect(Collectors.toSet());
    }

}
