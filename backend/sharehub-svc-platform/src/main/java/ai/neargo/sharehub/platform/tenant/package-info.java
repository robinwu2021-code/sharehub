/**
 * 平台域 · 租户（🔒 <b>休眠口子</b>，ADR-011）。
 *
 * <p>产品层<b>没有</b>租户概念：当前是单运营方，所有业务表的 {@code tenant_id} 恒为 {@code MAIN}。
 * 之所以仍然建表建实体，是因为隔离键一旦漏建，将来开多租户就是**破坏性数据迁移**——
 * 现在留一行，好过将来改一百张表。
 *
 * <p><b>因此端点只放 {@code /internal/platform/tenants}，不暴露到 {@code /api}</b>：
 * 运营端菜单里不该出现它，也不该有人从 UI 建出第二个租户。
 */
package ai.neargo.sharehub.platform.tenant;
