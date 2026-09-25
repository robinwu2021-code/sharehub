package ai.neargo.sharehub.api.core.port;

import java.util.Collection;
import java.util.Map;

/**
 * 「顺带显示是谁」—— {@code c_user_no} → 昵称的批量出口。
 *
 * <p>{@code user.core.NicknameLookup} 早就把这件事收敛成一个按批查询的组件，
 * 但它在 {@code user} 域里；{@code trade} 的列表（免费订单等）要用就得跨域，
 * 而 {@code user} 已经依赖 {@code trade}，直连即成包循环。
 *
 * <p><b>只出昵称不出手机号</b>：{@code phone} 落 {@code pb_pii.pii_user}，
 * 需 {@code pii:read} 且逐次审计（[api §1.6]），不随列表直出。
 */
public interface NicknameQueryPort {

    /** 批量取昵称；缺失的键不出现在结果里（调用方按 null 处理）。 */
    Map<String, String> byUserNos(Collection<String> cUserNos);
}
