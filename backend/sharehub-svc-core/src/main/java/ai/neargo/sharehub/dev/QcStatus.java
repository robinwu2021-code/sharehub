package ai.neargo.sharehub.dev;

/**
 * 入库质检状态（{@code dev_cabinet.qc_status} / {@code dev_powerbank.qc_status}，V107）。
 *
 * <p><b>null 不是一个值，是「质检上线之前就入库的存量」</b>：这批设备早已在跑，没有理由因为新规则把它们全部卡住。
 * 新建设备一律从 PENDING 开始；PENDING / FAILED 的设备不能发货调拨、不能上线。
 */
public enum QcStatus {
    PENDING, PASSED, FAILED;

    /** 是否放行（存量 null 视为放行）。 */
    public static boolean cleared(String v) {
        return v == null || PASSED.name().equals(v);
    }
}
