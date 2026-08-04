package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.dev.dto.DevDtos.CodeBatchRow;
import ai.neargo.sharehub.dev.entity.DevCodeBatch;
import ai.neargo.sharehub.dev.mapper.CodeBatchMapper;
import ai.neargo.sharehub.dev.service.CodeBatchService;
import org.springframework.stereotype.Service;

/** 设备编码批次实现：行为全部来自 {@link AbstractCrudService}，本类只声明键/搜索/筛选/转 VO。 */
@Service
public class CodeBatchServiceImpl extends AbstractCrudService<DevCodeBatch, CodeBatchRow>
        implements CodeBatchService {

    public CodeBatchServiceImpl(CodeBatchMapper mapper) {
        super(mapper);
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
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("PENDING");
        if (e.getCodeType() == null || e.getCodeType().isBlank()) e.setCodeType("QR");
        if (e.getTotal() == null) e.setTotal(0);
        if (e.getBound() == null) e.setBound(0);
    }

    @Override
    protected CodeBatchRow toVO(DevCodeBatch e) {
        return new CodeBatchRow(e.getBatchNo(), e.getVendorCode(), e.getCodeType(),
                e.getRangeStart(), e.getRangeEnd(), e.getTotal(), e.getBound(),
                e.getProducedAt(), e.getStatus());
    }
}
