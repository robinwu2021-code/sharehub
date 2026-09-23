package ai.neargo.sharehub.dev;

/**
 * 设备码批次的绑定进度（{@code dev_device_code_batch.status}，
 * DDL 注释 {@code 'PENDING/PARTIAL/BOUND/VOID'}）。
 *
 * <p>与 {@link OtaRolloutStatus#PENDING} 同名不同义：那里是"这次推送还没开始"，
 * 这里是"这批码还没有任何一个绑到设备上"。
 */
public enum CodeBatchStatus {

    /** 已生成，尚未绑定（DDL 默认值）。 */
    PENDING,
    /** 部分已绑定。 */
    PARTIAL,
    /** 全部已绑定。 */
    BOUND,
    /** 作废。 */
    VOID;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static CodeBatchStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("码批次状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("码批次状态非法: " + v + "（仅 PENDING/PARTIAL/BOUND/VOID）");
        }
    }
}
