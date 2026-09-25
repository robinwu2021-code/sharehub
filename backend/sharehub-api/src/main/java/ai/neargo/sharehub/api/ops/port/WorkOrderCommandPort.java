package ai.neargo.sharehub.api.ops.port;

/** 其它域要求开工单（站点撤场开撤机单）。wo 实现；幂等：同一站点同一台机柜只开一张。 */
public interface WorkOrderCommandPort {

    /** @return 工单号（已存在则返回已有的那张） */
    String openRemoval(String siteNo, String cabinetNo, String reason);
}
