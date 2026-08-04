/**
 * 平台域 · 系统配置（{@code sys_*} + {@code openapi_app}）。
 *
 * <p>装的是「运营可改、全站生效」的开关与阈值：系统参数、业务规则（三分区单例）、
 * 登录设置（按国家）、C端应用版本、税率与发票、开放平台应用。
 *
 * <p>三条不变量，改代码前先读：
 * <ol>
 *   <li>{@code sys_param} / {@code sys_login_setting} / {@code sys_tax_setting} 的自然键
 *       与 {@code tenant_id} 组成**复合唯一**（[db-design §2.1 注]）——
 *       按键查询必须带 tenantId，否则多租户开启即串数据；</li>
 *   <li>{@code sys_biz_rule} 的 {@code WITHDRAW} 分区是**提现手续费口径的唯一来源**，
 *       财务侧不得另存一份；</li>
 *   <li>版本回滚、拉黑解除一类「撤销」动作一律**软化**（改状态 + 留记录），全站零 DELETE。</li>
 * </ol>
 */
package ai.neargo.sharehub.platform.sys;
