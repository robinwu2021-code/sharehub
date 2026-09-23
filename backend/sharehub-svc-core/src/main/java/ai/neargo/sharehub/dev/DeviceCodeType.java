package ai.neargo.sharehub.dev;

/**
 * 设备码的形式（{@code dev_device_code_batch.code_type}，DDL 注释 {@code 'QR/SN'}）。
 */
public enum DeviceCodeType {

    /** 二维码 —— 贴在机柜上供 C 端扫码（DDL 默认值）。 */
    QR,
    /** 序列号 —— 出厂编号，用于资产盘点。 */
    SN;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static DeviceCodeType of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("设备码类型必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("设备码类型非法: " + v + "（仅 QR/SN）");
        }
    }
}
