package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.sharehub.dev.CodeBatchStatus;
import ai.neargo.sharehub.dev.DeviceCodeType;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.dev.dto.DevDtos.CodeBatchRow;
import ai.neargo.sharehub.dev.entity.DevCodeBatch;
import ai.neargo.sharehub.dev.mapper.CodeBatchMapper;
import ai.neargo.sharehub.dev.service.CodeBatchService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

/** 设备编码批次实现：行为全部来自 {@link AbstractCrudService}，本类只声明键/搜索/筛选/转 VO。 */
@Service
public class CodeBatchServiceImpl extends AbstractCrudService<DevCodeBatch, CodeBatchRow>
        implements CodeBatchService {

    public CodeBatchServiceImpl(CodeBatchMapper mapper, JdbcTemplate jdbc) {
        super(mapper, jdbc);
    }

    @Override
    protected String keyColumn() {
        return "batch_no";
    }

    @Override
    protected String keyOf(DevCodeBatch e) {
        return e.getBatchNo();
    }

    @Override
    protected void setKey(DevCodeBatch e, String no) {
        e.setBatchNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.CODE_BATCH; // BC0001…
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"batch_no", "range_start", "range_end"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"vendorCode", "codeType", "status"};
    }

    @Override
    protected void beforeCreate(DevCodeBatch e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(CodeBatchStatus.PENDING.name());
        if (e.getCodeType() == null || e.getCodeType().isBlank()) e.setCodeType(DeviceCodeType.QR.name());
        if (e.getTotal() == null) e.setTotal(0);
        if (e.getBound() == null) e.setBound(0);
        validateVocabulary(e);
    }

    /**
     * 编辑路径此前**一个校验都没有**（连 beforeUpdate 都没写），而本实体是请求体 ——
     * 词表外的值 PUT 一下就落库了。
     */
    @Override
    protected void beforeUpdate(DevCodeBatch e, DevCodeBatch current) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(current.getStatus());
        if (e.getCodeType() == null || e.getCodeType().isBlank()) e.setCodeType(current.getCodeType());
        validateVocabulary(e);
    }

    /**
     * 两列都进了 {@link #filterFields()} —— 也就是说列表页**按它们筛**。
     * 串一个字母的后果不是报错，是这一批码从筛选结果里消失：
     * 找不到就会有人再建一批，而那批码的号段与丢掉的那批重叠。
     */
    private static void validateVocabulary(DevCodeBatch e) {
        CodeBatchStatus.of(e.getStatus());
        DeviceCodeType.of(e.getCodeType());
    }

    @Override
    protected CodeBatchRow toVO(DevCodeBatch e) {
        return new CodeBatchRow(e.getBatchNo(), e.getVendorCode(), e.getCodeType(),
                e.getRangeStart(), e.getRangeEnd(), e.getTotal(), e.getBound(),
                e.getProducedAt(), e.getStatus());
    }
}
