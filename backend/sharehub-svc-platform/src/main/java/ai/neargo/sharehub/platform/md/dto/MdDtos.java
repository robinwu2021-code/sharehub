package ai.neargo.sharehub.platform.md.dto;

/**
 * platform/md 子域出参 VO。
 *
 * <p><b>约定</b>：新域一律用**域内 dto 文件**，不再往顶层 {@code dto/Dto.java} 追加
 * —— 那个文件是早期骨架的共享 DTO 集合，已冻结，多域并行修改会持续冲突。
 * 字段镜像 ops-web {@code lib/types/system.ts}。
 */
public final class MdDtos {

    private MdDtos() {
    }

    /** 银行字典行，镜像前端 {@code BankEntry}。 */
    public record BankEntry(String bankCode, String bankName, String bankNameEn,
                            String country, String currency, String swiftPrefix,
                            Integer ibanLength, String status) {
    }

    /**
     * 品牌行，镜像前端 {@code Brand}。
     *
     * @param marketCode 归属市场；本期不校验（待 S2 区域 → 市场链路）
     */
    public record BrandEntry(String brandNo, String name, String nameEn, String nameAr,
                             String logoUrl, String supportPhone, String marketCode,
                             String status, String archivedAt) {
    }
}
