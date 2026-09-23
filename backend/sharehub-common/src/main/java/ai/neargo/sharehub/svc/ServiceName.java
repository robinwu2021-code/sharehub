package ai.neargo.sharehub.svc;

/**
 * 进程名 —— 内部调用的寻址单位。
 *
 * <p>用常量而不是枚举：将来新增进程（协议对接的传输层拆分、支付实例）时，
 * 配置里出现一个没登记的名字应当是**配置错误**（`ServiceLocator` 报缺地址），
 * 而不是**编译错误**（枚举要改代码才能新增）。
 *
 * <p>名字同时是配置键的一部分：{@code sharehub.services.targets.<名>}。
 */
public final class ServiceName {

    /** 业务单体（:8082）。 */
    public static final String SHAREHUB = "SHAREHUB";

    /** 协议对接服务（:8091）—— S5 独立部署后才有地址，在那之前配置里不会有这一项。 */
    public static final String GATEWAY = "GATEWAY";

    private ServiceName() {
    }
}
