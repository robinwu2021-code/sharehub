package ai.neargo.sharehub.user.core.service;

import ai.neargo.sharehub.user.entity.UsrUser;
import ai.neargo.sharehub.user.mapper.UserMappers.UsrUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 昵称回填（{@code c_user_no} → {@code usr_user.nickname}）。
 *
 * <p>风控 / 黑名单 / 白名单 / 钱包 / 会员 五个列表都要「顺带显示是谁」，
 * 各自 join 一遍等于把 N+1 写五份 —— 收敛到一个按批查询的组件。
 *
 * <p><b>为什么不落冗余 {@code nickname} 列</b>：[db-design §1.4] 的展示名冗余只用于
 * <b>历史单据</b>（如 {@code usr_recharge_order.nickname} 是下单时快照）；
 * 用户属性类列表要的是<b>当前</b>昵称，冗余会漂移。
 *
 * <p><b>手机号不在这里</b>：{@code phone} 落 {@code pb_pii.pii_user}，
 * 需 {@code pii:read} 且逐次审计（[api §1.6]），不随列表直出。
 */
@Component
public class NicknameLookup {

    private final UsrUserMapper users;

    public NicknameLookup(UsrUserMapper users) {
        this.users = users;
    }

    /** 批量取昵称；缺失的键不出现在结果里（调用方按 null 处理）。 */
    public Map<String, String> byUserNos(Collection<String> userNos) {
        List<String> keys = userNos.stream().filter(n -> n != null && !n.isBlank()).distinct().toList();
        if (keys.isEmpty()) return Map.of();
        return users.selectList(new LambdaQueryWrapper<UsrUser>().in(UsrUser::getCUserNo, keys)).stream()
                .filter(u -> u.getNickname() != null)
                .collect(Collectors.toMap(UsrUser::getCUserNo, UsrUser::getNickname, (a, b) -> a));
    }

    /** 单个取昵称；不存在返回 null。 */
    public String byUserNo(String userNo) {
        return byUserNos(List.of(userNo == null ? "" : userNo)).get(userNo);
    }
}
