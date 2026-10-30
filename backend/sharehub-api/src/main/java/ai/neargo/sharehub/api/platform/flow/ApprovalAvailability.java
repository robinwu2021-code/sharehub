package ai.neargo.sharehub.api.platform.flow;

/** 统一审批连接配置可用性；业务不得在不可用时切换为本地审批。 */
public interface ApprovalAvailability {
    boolean configured();
}
