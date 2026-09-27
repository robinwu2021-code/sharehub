package ai.neargo.opskit.process.api.definition;

/**
 * 审批人 / 候选人的种类。会签（ALL）要求能枚举出具体的人：只有 MEMBER、GROUP（进入步骤时快照组员）、FIELD 可以；
 * ROLE、PERM 由令牌与目录动态决定，枚举不出「全部」（21/02 §6.4）。
 */
public enum ApproverKind {
    MEMBER, GROUP, ROLE, PERM, FIELD, PARTICIPANT;

    public boolean enumerable() {
        return this == MEMBER || this == GROUP || this == FIELD;
    }
}
