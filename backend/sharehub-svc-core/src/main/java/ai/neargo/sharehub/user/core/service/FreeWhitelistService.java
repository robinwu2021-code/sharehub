package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.FreeUserWhitelist;
import ai.neargo.sharehub.user.core.entity.UsrFreeWhitelist;

/**
 * 免费用户白名单（usr_free_whitelist）。免单直接等于收入减记 → 有业务规则，手写。
 *
 * <p><b>两条硬规则</b>：
 * <ol>
 *   <li><b>撤销是软删</b>：{@code status=REVOKED} 保留记录，不物理删 —— 事后要能查「谁在什么时候
 *       授了谁多少额度、用掉了多少」。对应端点 {@code POST /free-whitelist/{userNo}/revoke}。</li>
 *   <li><b>{@code quotaType} 与 {@code quotaValue} 联动</b>：{@code UNLIMITED} 时 {@code quotaValue}
 *       无意义（强制归零，防止「无限额度 + 残留数值」在报表里被误当上限）；
 *       {@code TIMES}/{@code AMOUNT} 时 {@code quotaValue} 必须 &gt; 0。</li>
 * </ol>
 */
public interface FreeWhitelistService {

    PageResult<FreeUserWhitelist> page(Integer page, Integer size, String keyword,
                                       String reason, String status);

    /** 按 C 端用户号取当前生效记录；不存在返回 {@code null}。 */
    FreeUserWhitelist get(String cUserNo);

    /**
     * 授予 / 修改白名单（upsert，按 {@code cUserNo} 寻址 —— 端点是 {@code /free-whitelist/{userNo}}）。
     *
     * @throws IllegalArgumentException {@code quotaType}/{@code quotaValue} 不联动，或 reason 不在枚举内
     */
    FreeUserWhitelist save(UsrFreeWhitelist body, String operatorNo);

    /**
     * 撤销（软删除：{@code status=REVOKED}，记录保留）。
     *
     * @throws IllegalStateException 该用户没有 ACTIVE 白名单
     */
    FreeUserWhitelist revoke(String cUserNo, String operatorNo);
}
