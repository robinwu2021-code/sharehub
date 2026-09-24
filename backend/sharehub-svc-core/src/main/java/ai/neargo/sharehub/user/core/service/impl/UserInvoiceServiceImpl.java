package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.sharehub.user.core.dto.UserCoreDtos;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.InvoiceItem;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.InvoiceTitleItem;
import ai.neargo.sharehub.user.core.entity.UsrInvoice;
import ai.neargo.sharehub.user.core.entity.UsrInvoiceTitle;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrInvoiceMapper;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrInvoiceTitleMapper;
import ai.neargo.sharehub.user.core.service.BizNoAllocator;
import ai.neargo.sharehub.user.core.service.UserInvoiceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 抬头 + 开票实现。 */
@Service
public class UserInvoiceServiceImpl implements UserInvoiceService {

    /** 抬头业务键前缀 —— [db-design §1.4.1] 尚未登记，暂用 {@code ITL}，见交付报告的「规格缺漏」。 */
    private static final String TITLE_PREFIX = "ITL";
    private static final String TENANT_MAIN = "MAIN";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UsrInvoiceTitleMapper titles;
    private final UsrInvoiceMapper invoices;

    public UserInvoiceServiceImpl(UsrInvoiceTitleMapper titles, UsrInvoiceMapper invoices) {
        this.titles = titles;
        this.invoices = invoices;
    }

    @Override
    public PageResult<InvoiceTitleItem> pageTitles(String cUserNo, Integer page, Integer size) {
        Page<UsrInvoiceTitle> r = titles.selectPage(pageOf(page, size),
                new LambdaQueryWrapper<UsrInvoiceTitle>()
                        .eq(UsrInvoiceTitle::getCUserNo, cUserNo)
                        .orderByDesc(UsrInvoiceTitle::getIsDefault)
                        .orderByDesc(UsrInvoiceTitle::getId));
        List<InvoiceTitleItem> rows = r.getRecords().stream().map(UserInvoiceServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    @Transactional
    public InvoiceTitleItem saveTitle(String cUserNo, UsrInvoiceTitle body) {
        if ("COMPANY".equals(body.getType()) && (body.getVatTrn() == null || body.getVatTrn().isBlank())) {
            throw new IllegalArgumentException("企业抬头必须填写税号 vatTrn");
        }
        body.setCUserNo(cUserNo); // 属主以会话为准，不信 body

        UsrInvoiceTitle current = body.getTitleNo() == null ? null : titles.selectOne(
                new LambdaQueryWrapper<UsrInvoiceTitle>()
                        .eq(UsrInvoiceTitle::getTitleNo, body.getTitleNo())
                        .eq(UsrInvoiceTitle::getCUserNo, cUserNo)
                        .last("limit 1"));

        if (current == null) {
            body.setTitleNo(BizNoAllocator.next(titles, "title_no", TITLE_PREFIX, UsrInvoiceTitle::getTitleNo));
            body.setTenantId(TENANT_MAIN);
            titles.insert(body);
        } else {
            body.setId(current.getId());
            body.setVersion(current.getVersion());
            body.setTenantId(current.getTenantId());
            titles.updateById(body);
        }

        if (Integer.valueOf(1).equals(body.getIsDefault())) {
            // 「默认抬头」是单选：设新默认必须把旧默认降级，否则开票时取到哪条全凭排序运气
            titles.update(null, new LambdaUpdateWrapper<UsrInvoiceTitle>()
                    .eq(UsrInvoiceTitle::getCUserNo, cUserNo)
                    .ne(UsrInvoiceTitle::getTitleNo, body.getTitleNo())
                    .set(UsrInvoiceTitle::getIsDefault, 0));
        }
        return toVO(body);
    }

    @Override
    public PageResult<InvoiceItem> pageInvoices(String cUserNo, Integer page, Integer size, String status) {
        LambdaQueryWrapper<UsrInvoice> w = new LambdaQueryWrapper<UsrInvoice>()
                .eq(UsrInvoice::getCUserNo, cUserNo);
        if (status != null && !status.isBlank()) w.eq(UsrInvoice::getStatus, status);
        w.orderByDesc(UsrInvoice::getId);

        Page<UsrInvoice> r = invoices.selectPage(pageOf(page, size), w);
        List<InvoiceItem> rows = r.getRecords().stream().map(UserInvoiceServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public InvoiceItem apply(String cUserNo, UserCoreDtos.InvoiceApplyReq req) {
        /*
         * **入参是白名单，不再是实体。**
         * 这是 C 端端点，构造请求的是终端用户。此前收 UsrInvoice 实体，
         * 而 fileUrl 没有任何服务端写入路径（setFileUrl 全后端没人调）——
         * 它唯一的来源就是请求体，用户能在申请时塞一个自己的 URL 进去。
         */
        UsrInvoice body = new UsrInvoice();
        body.setTitleNo(req.titleNo());
        body.setAmount(req.amount());
        body.setCurrency(req.currency());

        UsrInvoiceTitle title = titles.selectOne(new LambdaQueryWrapper<UsrInvoiceTitle>()
                .eq(UsrInvoiceTitle::getTitleNo, body.getTitleNo())
                .eq(UsrInvoiceTitle::getCUserNo, cUserNo)
                .last("limit 1"));
        if (title == null) throw new IllegalArgumentException("抬头不存在或不属于当前用户: " + body.getTitleNo());

        body.setInvoiceNo(BizNoAllocator.next(invoices, "invoice_no", BizKey.INVOICE_USER, UsrInvoice::getInvoiceNo));
        body.setTenantId(TENANT_MAIN);
        body.setCUserNo(cUserNo);
        body.setTitle(title.getTitle()); // 抬头快照，不随抬头改名回溯
        body.setStatus("APPLIED");
        body.setAppliedAt(LocalDateTime.now().format(TS));
        body.setIssuedAt(null);
        if (body.getAmount() == null) body.setAmount(BigDecimal.ZERO);
        invoices.insert(body);
        // TODO(关联订单)：所选订单落 fin_invoice_item(invoice_no, order_no, invoice_side=USER)，
        //  并在此校验「订单已结算 + 未开过票」——待 fin 子域的 InvoiceItemService 落地后接上。
        return toVO(body);
    }

    private static <T> Page<T> pageOf(Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        return new Page<>(p, s);
    }

    private static InvoiceTitleItem toVO(UsrInvoiceTitle e) {
        return new InvoiceTitleItem(e.getTitleNo(), e.getType(), e.getTitle(), e.getVatTrn(),
                Integer.valueOf(1).equals(e.getIsDefault()));
    }

    private static InvoiceItem toVO(UsrInvoice e) {
        return new InvoiceItem(e.getInvoiceNo(), e.getTitleNo(), e.getTitle(), e.getAmount(),
                e.getCurrency(), e.getStatus(), e.getFileUrl(), e.getAppliedAt(), e.getIssuedAt());
    }
}
