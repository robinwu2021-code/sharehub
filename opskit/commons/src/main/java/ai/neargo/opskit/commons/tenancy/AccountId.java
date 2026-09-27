package ai.neargo.opskit.commons.tenancy;

/** 账户（签约主体）的对外 ID。类型化是为了让仓储签名上「漏传租户」在编译期就暴露。 */
public record AccountId(String value) {

    /**
     * 认证之前还不知道账户时的占位（如按凭证哈希找应用）。只能用于这类入口查询，
     * 让「这条查询没有租户条件」在调用处显形，而不是悄悄绕过 A6。
     */
    public static final AccountId UNKNOWN = new AccountId("-unknown-");

    public AccountId {
        Ids.requireValid(value, "accountId");
    }

    @Override
    public String toString() {
        return value;
    }
}
