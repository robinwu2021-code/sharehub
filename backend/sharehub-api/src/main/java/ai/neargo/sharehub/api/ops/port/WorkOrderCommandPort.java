package ai.neargo.sharehub.api.ops.port;

/** 其它域要求开工单（站点撤场开撤机单）。wo 实现；幂等：同一站点同一台机柜只开一张。 */
public interface WorkOrderCommandPort {

    /** @return 工单号（已存在则返回已有的那张） */
    String openRemoval(String siteNo, String cabinetNo, String reason);

    /**
     * 绑定时同事务开装机单（V164）。站点与商家由工单服务从机柜反查（机柜此时已绑好点位）。
     * 幂等：同一台柜子绑同一个点位只开一张。
     */
    String openInstall(String cabinetNo, String locationNo, String locationName, String expectedAt);
}
