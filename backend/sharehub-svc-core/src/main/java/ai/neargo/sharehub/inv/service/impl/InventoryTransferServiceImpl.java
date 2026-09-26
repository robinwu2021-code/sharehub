package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.inv.InvTransferStateMachine;
import ai.neargo.sharehub.inv.InvTransferStatus;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.sharehub.inv.dto.InvDtos.InvTransferReq;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransfer;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransferDetail;
import ai.neargo.sharehub.inv.dto.InvDtos.TransferItem;
import ai.neargo.sharehub.inv.entity.InvTransfer;
import ai.neargo.sharehub.inv.entity.InvTransferItem;
import ai.neargo.sharehub.inv.mapper.InvTransferItemMapper;
import ai.neargo.sharehub.inv.mapper.InvTransferMapper;
import ai.neargo.sharehub.inv.service.InventoryTransferService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 库存调拨实现。手写 {@code LambdaQueryWrapper}（聚合根路线），状态流转经
 * {@link InvTransferStateMachine}，非法迁移抛异常。
 */
@Service
public class InventoryTransferServiceImpl implements InventoryTransferService {

    /** 单运营方，隔离键恒为 MAIN（ADR-011 休眠口子），与 {@code AbstractCrudService} 保持一致。 */
    private static final String TENANT_MAIN = "MAIN";

    private static final Set<String> LOCATION_TYPES = Set.of("WAREHOUSE", "SITE", "LOCATION");
    private static final Set<String> ITEM_TYPES = Set.of("CABINET", "POWERBANK");

    private final InvTransferMapper mapper;
    private final InvTransferItemMapper itemMapper;
    private final InvTransferStateMachine stateMachine;

    public InventoryTransferServiceImpl(InvTransferMapper mapper, InvTransferItemMapper itemMapper,
                                        InvTransferStateMachine stateMachine) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public PageResult<InventoryTransfer> page(Integer page, Integer size, String keyword,
                                              String status, String itemType) {
        LambdaQueryWrapper<InvTransfer> w = new LambdaQueryWrapper<>();
        if (notBlank(keyword)) {
            // 单号 + 两端快照名：现场的人记得住「从三号仓调到万达」，记不住 TR0042
            w.and(q -> q.like(InvTransfer::getTransferNo, keyword)
                    .or().like(InvTransfer::getFromName, keyword)
                    .or().like(InvTransfer::getToName, keyword));
        }
        if (notBlank(status)) w.eq(InvTransfer::getStatus, status);
        if (notBlank(itemType)) w.eq(InvTransfer::getItemType, itemType);
        w.orderByDesc(InvTransfer::getId);

        Page<InvTransfer> r = mapper.selectPage(new Page<>(norm(page), normSize(size)), w);
        List<InventoryTransfer> rows = r.getRecords().stream()
                .map(InventoryTransferServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public InventoryTransferDetail get(String transferNo) {
        InvTransfer e = selectByNo(transferNo);
        if (e == null) return null;
        List<TransferItem> items = selectItems(transferNo).stream()
                .map(i -> new TransferItem(i.getTransferNo(), itemNoOf(i),
                        i.getChecked() != null && i.getChecked() == 1))
                .toList();
        return new InventoryTransferDetail(toVO(e), items);
    }

    @Override
    public InventoryTransfer save(String transferNo, InvTransferReq body) {
        String no = transferNo;

        // —— 建单 ——
        if (!notBlank(no)) {
            InvTransfer e = new InvTransfer();
            e.setFromType(body.fromType());
            e.setFromRef(body.fromRef());
            e.setFromName(body.fromName());
            e.setToType(body.toType());
            e.setToRef(body.toRef());
            e.setToName(body.toName());
            e.setItemType(body.itemType());
            e.setPowerbankCount(body.powerbankCount() == null ? 0 : body.powerbankCount());
            validateEndpoints(e);
            e.setTransferNo(nextTransferNo());
            e.setTenantId(TENANT_MAIN);
            e.setStatus(InvTransferStatus.DRAFT.name()); // 建单一律 DRAFT，不接受调用方直接开在途单
            // 经办人按当前登录人落，不收请求体里的值 —— 搬设备的人要能对上号
            e.setOperatorNo(StaffContext.require().userNo());
            mapper.insert(e);
            return toVO(selectByNo(e.getTransferNo()));
        }

        // —— 更新 ——
        InvTransfer current = selectByNo(no);
        if (current == null) throw BizException.notFound(no);
        if (stateMachine.isTerminal(current.getStatus())) {
            throw new IllegalArgumentException("调拨单已完成，不可再修改: " + no
                    + "（如需退回请开一张反向调拨单，保留两条痕）");
        }

        String target = body.status();
        if (notBlank(target) && !target.equals(current.getStatus())) {
            // 目标状态 → 事件，交状态机裁决；未定义的跃迁（如 DRAFT 直接到 DONE）在这里被拒
            // switch 改切在枚举上：非法字符串在 of() 就被挡住并说清楚哪个值不合法，
            // 而不是一路走到 default 报「不支持的目标状态」——后者把「拼错了」
            // 与「这一步不让走」混成同一句话。
            String event = switch (InvTransferStatus.of(target)) {
                case IN_TRANSIT -> "SHIP";
                case DONE -> "RECEIVE";
                default -> throw new IllegalArgumentException("不支持的目标状态: " + target);
            };
            /*
             * **发货有两个入口**：专用的 `POST …/{no}/ship` 与这里的「保存时传目标状态」。
             * 空单校验此前只加在前者，于是这条路照样能把零明细的单推成在途 ——
             * 「一个动作两个入口，只堵一个」是最典型的漏法，而账面上完全自洽
             * （应收 0 件、实收 0 件），对账查不出来。
             */
            if ("SHIP".equals(event)) requireHasItems(no);
            if ("RECEIVE".equals(event)) requireAllChecked(no);
            current.setStatus(stateMachine.next(current.getStatus(), event));
        }

        // 单头可改字段：只有 DRAFT 期允许改两端与数量，在途单改数量等于事后编账
        if (InvTransferStatus.DRAFT.name().equals(current.getStatus())) {
            if (notBlank(body.fromType())) current.setFromType(body.fromType());
            if (notBlank(body.fromRef())) current.setFromRef(body.fromRef());
            if (notBlank(body.fromName())) current.setFromName(body.fromName());
            if (notBlank(body.toType())) current.setToType(body.toType());
            if (notBlank(body.toRef())) current.setToRef(body.toRef());
            if (notBlank(body.toName())) current.setToName(body.toName());
            if (notBlank(body.itemType())) current.setItemType(body.itemType());
            if (body.powerbankCount() != null) current.setPowerbankCount(body.powerbankCount());
            validateEndpoints(current);
        }
        // 经办人不在写入面里：建单时落的那个人就是经办人，换人经办得换个人来操作

        mapper.updateById(current);

        // TODO(跨分片依赖 · 设备域)：DONE 时应同步 inv_stock 结存与设备归属
        //   出库仓 qty -= n、入库仓 qty += n（UK(warehouse_no,item_type,model) 定位行）；
        //   itemType=CABINET 时机柜 status 在 IN_STOCK ↔ DEPLOYED 之间流转（db-design §9A.2）。
        //   dev_cabinet / dev_powerbank 归其他分片，此处不跨包直写。

        return toVO(selectByNo(no));
    }

    // ——————————————————————— 内部 ———————————————————————

    private InvTransfer selectByNo(String transferNo) {
        if (!notBlank(transferNo)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<InvTransfer>()
                .eq(InvTransfer::getTransferNo, transferNo).last("limit 1"));
    }

    private List<InvTransferItem> selectItems(String transferNo) {
        return itemMapper.selectList(new LambdaQueryWrapper<InvTransferItem>()
                .eq(InvTransferItem::getTransferNo, transferNo)
                .orderByAsc(InvTransferItem::getId));
    }

    /** 发货前必须有明细：接收方等着收货，而车上什么都没有。与 {@code TransferOpsServiceImpl#ship} 同一条闸。 */
    private void requireHasItems(String transferNo) {
        if (selectItems(transferNo).isEmpty()) {
            throw BizException.conflict("error.inv_transfer.no_items", transferNo);
        }
    }

    /** 收货前逐件核对：有一件没勾就不许结单 —— 这正是「少了一台」当场被发现的地方。 */
    private void requireAllChecked(String transferNo) {
        List<InvTransferItem> items = selectItems(transferNo);
        if (items.isEmpty()) return; // 无明细的单（按件数记账）不做逐件校验
        long unchecked = items.stream().filter(i -> i.getChecked() == null || i.getChecked() != 1).count();
        if (unchecked > 0) {
            throw new IllegalArgumentException("尚有 " + unchecked + " 件未签收核对，不能结单: " + transferNo);
        }
    }

    private static void validateEndpoints(InvTransfer e) {
        if (!LOCATION_TYPES.contains(e.getFromType()) || !LOCATION_TYPES.contains(e.getToType())) {
            throw new IllegalArgumentException("fromType/toType 必须是 WAREHOUSE/SITE/LOCATION 之一");
        }
        if (!notBlank(e.getFromRef()) || !notBlank(e.getToRef())) {
            throw new IllegalArgumentException("fromRef/toRef 不能为空");
        }
        if (e.getFromType().equals(e.getToType()) && e.getFromRef().equals(e.getToRef())) {
            throw new IllegalArgumentException("调出方与调入方不能相同");
        }
        if (!ITEM_TYPES.contains(e.getItemType())) {
            throw new IllegalArgumentException("itemType 必须是 CABINET/POWERBANK 之一");
        }
    }

    /**
     * 取号：扫描同前缀最大号 +1（[db-design §1.4.1]）。
     * <b>禁止</b>「前缀 + 行数」——那个写法在前端 mock 已导致过两次主键撞号。
     * 并发下仍可能撞，靠 {@code uk_transfer_no} 兜底。
     */
    private String nextTransferNo() {
        InvTransfer top = mapper.selectOne(new LambdaQueryWrapper<InvTransfer>()
                .likeRight(InvTransfer::getTransferNo, BizKey.TRANSFER)
                .orderByDesc(InvTransfer::getTransferNo)
                .last("limit 1"));
        long n = 0L;
        if (top != null && top.getTransferNo() != null
                && top.getTransferNo().length() > BizKey.TRANSFER.length()) {
            String digits = top.getTransferNo().substring(BizKey.TRANSFER.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号（非纯数字后缀）不参与取号，由 UNIQUE 兜底
                }
            }
        }
        return BizKey.TRANSFER + String.format("%04d", n + 1);
    }

    private static String itemNoOf(InvTransferItem i) {
        return notBlank(i.getPowerbankNo()) ? i.getPowerbankNo() : i.getCabinetNo();
    }

    private static InventoryTransfer toVO(InvTransfer e) {
        return new InventoryTransfer(e.getTransferNo(),
                e.getFromName(), e.getToName(),
                e.getFromType(), e.getFromRef(),
                e.getToType(), e.getToRef(),
                e.getItemType(), e.getPowerbankCount(),
                e.getStatus(), e.getOperatorNo(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int norm(Integer v) {
        return (v == null || v < 1) ? 1 : v;
    }

    private static int normSize(Integer v) {
        return (v == null || v < 1) ? 10 : Math.min(v, 200);
    }
}
