package ai.neargo.sharehub.loc.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 合同附件元数据（{@code loc_contract_attach}）。
 *
 * <p><b>只存元数据，不存字节流</b>：前端契约明确「故意没有 url/storageKey」——
 * 接对象存储时再补 {@code storageKey}。现在编一个假地址的话，
 * 「点开看不了」比「明确没有下载入口」更难查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("loc_contract_attach")
public class LocContractAttach extends BaseEntity {

    private String attachNo;
    private String contractNo;
    private String fileName;

    /** 字节数。由前端 {@code File.size} 直取，不做换算，展示层再格式化。 */
    private Long size;

    private String uploadedBy;
    private LocalDateTime uploadedAt;

    /** → sys_file.file_no（V94）。空 = 接入对象存储前的历史附件，只有名字没有字节。 */
    private String fileNo;
}
