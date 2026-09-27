package ai.neargo.opskit.commons.tenancy;

/**
 * 空间（数据隔离单位）的对外 ID。
 *
 * <p>{@link #TEMPLATE} 表示「账户级模板」：定义表里用空串占位，而不是 NULL，
 * 因为唯一键里的 NULL 不参与比较，会让同一账户下出现两份同代号的模板。
 */
public record WorkspaceId(String value) {
    public static final WorkspaceId TEMPLATE = new WorkspaceId("");

    public WorkspaceId {
        if (value == null) {
            throw new IllegalArgumentException("workspaceId must not be null");
        }
        if (!value.isEmpty()) {
            Ids.requireValid(value, "workspaceId");
        }
    }

    public boolean isTemplate() {
        return value.isEmpty();
    }

    @Override
    public String toString() {
        return value;
    }
}
