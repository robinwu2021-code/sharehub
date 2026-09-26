package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.CUserRow;
import ai.neargo.sharehub.user.core.entity.UsrBlacklist;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrBlacklistMapper;
import ai.neargo.sharehub.user.core.service.UserQueryService;
import ai.neargo.sharehub.user.entity.UsrUser;
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

    // usr_identity 不再被注入：它此前唯一的用途是拿 provider_uid 冒充手机号（见 NO_PHONE_SOURCE）。
    // 留着一个没人用的 mapper，下一个人会以为这里还要读身份表。
    public UserQueryServiceImpl(UsrUserMapper users, UsrBlacklistMapper blacklists) {
        this.users = users;
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
        if (u == null) throw BizException.notFound(cUserNo);
        if (nickname != null && !nickname.isBlank()) u.setNickname(nickname.trim());
        if (avatar != null && !avatar.isBlank()) u.setAvatar(avatar.trim());
        users.updateById(u);
        return rows(List.of(u)).get(0);
    }

    /**
     * 手机号**没有明文来源**，所以一律出空串。
     *
     * <h3>它此前是什么</h3>
     * 这里原来是 {@code mask(identity.providerUid)} —— 把 {@code usr_identity.provider_uid}
     * 当手机号去掩码。而那一列存的是**登录标识的哈希**（HMAC-SHA256 的十六进制），
     * 从来不是手机号。于是：
     * <ul>
     *   <li>C 端「我的资料」显示 {@code 056****7c12}（2026-09-27 H5 对接实测所见）；</li>
     *   <li>运营端用户 / 风控 / 黑名单 / 白名单**四张表**的「手机」列，
     *       每个消费者都是这种十六进制尾巴。</li>
     * </ul>
     * 掩码把它伪装得很像一个真手机号 —— <b>这正是它一直没被发现的原因</b>：
     * 一个打了码的号，谁也不会去核对中间那几位对不对。
     *
     * <p>旧代码的注释自己就写着「明文只在 pb_pii」，而 {@code pb_pii} 分库**从没建过**
     * （架构级 D4）。也就是说这个字段自始至终没有数据源。
     *
     * <h3>为什么是空串而不是继续掩码</h3>
     * 同一个方法上面几行的 {@code email} 就是这么处理的：「无列（pb_pii 未建），如实出 null」。
     * 空着能看出缺了什么，伪装成手机号看不出。
     * pb_pii 落地（或另立明文列）之后，这里换成真来源即可。
     */
    private static final String NO_PHONE_SOURCE = "";

    private List<CUserRow> rows(List<UsrUser> list) {
        if (list.isEmpty()) return List.of();
        Set<String> nos = list.stream().map(UsrUser::getCUserNo).collect(Collectors.toSet());
        Set<String> black = activeBlacklist(nos);
        return list.stream().map(u -> new CUserRow(
                u.getCUserNo(), u.getNickname(), u.getAvatar(), NO_PHONE_SOURCE,
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
