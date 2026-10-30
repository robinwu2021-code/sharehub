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
import ai.neargo.sharehub.common.crud.BizNoSequence;

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
    private final ai.neargo.sharehub.inv.TransferAccess access;

    /** 取号专用 —— 必须绕开逻辑删除过滤，见 nextTransferNo。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public InventoryTransferServiceImpl(InvTransferMapper mapper, InvTransferItemMapper itemMapper,
                                        InvTransferStateMachine stateMachine,
                                        org.springframework.jdbc.core.JdbcTemplate jdbc, ai.neargo.sharehub.inv.TransferAccess access) {
        this.access = access;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public PageResult<InventoryTransfer> page(Integer page, Integer size, String keyword,
                                              String status, String itemType) {
        LambdaQueryWrapper<InvTransfer> w = new LambdaQueryWrapper<>();
        access.filter(w);
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
        access.read(e);
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
            access.edit(e);
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
        access.edit(current);
        if (ai.neargo.sharehub.inv.TransferSource.REMOVAL.name().equals(current.getSourceType()))
            throw BizException.conflict("error.common.state_changed");
        if (stateMachine.isTerminal(current.getStatus())) {
            throw new IllegalArgumentException("调拨单已完成，不可再修改: " + no
                    + "（如需退回请开一张反向调拨单，保留两条痕）");
        }

        // 保存单头不得代替发货／签收，否则只改单据而漏设备位置、差异和库存账。
        if (notBlank(body.status()) && !body.status().equals(current.getStatus())) {
            throw BizException.conflict("error.inv_transfer.action_required", no);
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
            access.edit(current);
        }
        // 经办人不在写入面里：建单时落的那个人就是经办人，换人经办得换个人来操作

        mapper.updateById(current);

        // 状态与记账只在 TransferOpsService.ship/receive 中执行。

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
        requireSupportedPowerbankEndpoints(e);
        if (!ITEM_TYPES.contains(e.getItemType())) {
            throw new IllegalArgumentException("itemType 必须是 CABINET/POWERBANK 之一");
        }
    }

    static void requireSupportedPowerbankEndpoints(InvTransfer t) {
        if (ai.neargo.sharehub.dev.movement.MovementRecorder.POWERBANK.equals(t.getItemType())
                && (!ai.neargo.sharehub.inv.TransferEndpointType.WAREHOUSE.name().equals(t.getFromType())
                || !ai.neargo.sharehub.inv.TransferEndpointType.WAREHOUSE.name().equals(t.getToType())))
            throw BizException.badRequest("error.common.invalid_value", "POWERBANK transfer endpoints: WAREHOUSE");
    }

    /**
     * 取号：扫描同前缀最大号 +1（[db-design §1.4.1]）。
     * <b>禁止</b>「前缀 + 行数」——那个写法在前端 mock 已导致过两次主键撞号。
     * 并发下仍可能撞，靠 {@code uk_transfer_no} 兜底。
     *
     * <h3>为什么委托给 BizNoSequence</h3>
     * 原来这里用 {@code mapper.selectOne(...)} 自己扫，有两处**潜伏**的错：
     *
     * <ol>
     *   <li><b>按字典序取最大</b>（{@code orderByDesc(transferNo)}）。位宽一律 4 位时侥幸正确，
     *       一旦越过 {@code TR9999} 变成 5 位就取错 —— {@code 'TR9999' > 'TR10000'}。
     *       {@link BizNoSequence} 按**数字序**取，没有这个问题。</li>
     *   <li><b>看不见软删的号</b>。{@code @TableLogic} 会给 QueryWrapper 自动补
     *       {@code deleted = 0}，而 {@code uk_transfer_no} 不含 deleted。
     *       今天这张表**没有删除入口**，所以这一条还不成立；但列和过滤都已经在了，
     *       哪天加一个归档功能就会立刻成立 —— 同一个病在本仓已经咬过两次
     *       （代理商取号、{@code AbstractCrudService}）。</li>
     * </ol>
     *
     * <h3>⚠️ 它不修 2026-09-27 那次 TR0002 撞号</h3>
     * 那天实测到 {@code Duplicate entry 'TR0002' for key 'uk_transfer_no'}，
     * 第一反应是「又是软删的洞」—— <b>查下来不是</b>：{@code inv_transfer} 69 行
     * <b>一行软删都没有</b>，号段 TR0001–TR0069 连续。
     * 最可能的原因是**两个会话共用同一个 test 库并发跑测试**
     * （{@code application-test.yml} 就提醒过各自设 {@code SHAREHUB_TEST_DB}）。
     *
     * <p>而 {@link BizNoSequence} 的类注释写明了：**并发下它照样会撞**，靠唯一键兜底。
     * 所以本次改动是修上面那两条潜伏的错，<b>不是</b>修那次报错 ——
     * 把它写成「修好了」会让下次同样的撞号被当成已解决而不再追。
     */
    private String nextTransferNo() {
        return BizNoSequence.next(jdbc, "inv_transfer", "transfer_no", BizKey.TRANSFER, 4);
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
