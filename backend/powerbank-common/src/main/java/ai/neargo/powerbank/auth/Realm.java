package ai.neargo.powerbank.auth;

/**
 * 身份域（凭据池，对应 pb_auth.cred_credential.realm）。
 * 运营端员工/运维 = STAFF；代理端 = AGENT；C 端消费者 = CONSUMER。
 * 见 docs/technical/权限管理方案.md §1。
 */
public enum Realm {
    STAFF, AGENT, CONSUMER
}
