package ai.neargo.opskit.process.api.definition;

/**
 * 状态类别（21/02 §6.1）。通用行为只看类别、不看状态名：SLA 暂停看 WAITING，停表与关闭看 DONE / CANCELLED，
 * 统计口径也按类别 —— 各租户的状态名千差万别，类别是唯一能写死在引擎里的东西。
 */
public enum StatusCategory {
    NEW, IN_PROGRESS, WAITING, DONE, CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }
}
