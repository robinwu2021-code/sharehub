package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.inv.ShipmentDocType;
import ai.neargo.sharehub.inv.dto.ShipmentDtos.ShipInfo;
import ai.neargo.sharehub.inv.dto.ShipmentDtos.Shipment;
import ai.neargo.sharehub.inv.entity.InvShipment;
import ai.neargo.sharehub.inv.entity.InvShipmentItem;
import ai.neargo.sharehub.inv.mapper.InvShipmentItemMapper;
import ai.neargo.sharehub.inv.mapper.InvShipmentMapper;
import ai.neargo.sharehub.inv.service.ShipmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 发货物流（V205）。 */
@Service
public class ShipmentServiceImpl implements ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentServiceImpl.class);

    private final InvShipmentMapper shipments;
    private final InvShipmentItemMapper items;

    public ShipmentServiceImpl(InvShipmentMapper shipments, InvShipmentItemMapper items) {
        this.shipments = shipments;
        this.items = items;
    }

    @Override
    public void validate(ShipInfo i) {
        if (i == null) return;
        if (len(i.carrier()) > 64) throw BizException.badRequest("error.common.invalid_value", "carrier");
        if (len(i.trackingNo()) > 64) throw BizException.badRequest("error.common.invalid_value", "trackingNo");
        if (len(i.note()) > 512) throw BizException.badRequest("error.common.invalid_value", "note");
        shippedAt(i);
    }

    @Override
    @Transactional
    public Shipment record(ShipmentDocType docType, String docRef, Map<String, List<String>> itemsByType, ShipInfo i) {
        if (i == null || (blank(i.carrier()) && blank(i.trackingNo()))) return null;
        validate(i);
        Map<String, List<String>> byType = new java.util.LinkedHashMap<>();
        (itemsByType == null ? Map.<String, List<String>>of() : itemsByType).forEach((type, xs) -> byType.put(type,
                xs == null ? List.of() : xs.stream().filter(s -> !blank(s)).map(String::trim).distinct().toList()));
        List<String> nos = byType.values().stream().flatMap(List::stream).toList();
        InvShipment s = new InvShipment();
        s.setShipmentNo(IdGenerator.next("SH"));
        s.setTenantId("MAIN");
        s.setDocType(docType.name());
        s.setDocRef(docRef);
        s.setCarrier(trim(i.carrier()));
        s.setTrackingNo(trim(i.trackingNo()));
        s.setShippedAt(shippedAt(i));
        s.setNote(trim(i.note()));
        s.setItemCount(nos.size());
        s.setCreatedBy(SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM"));
        shipments.insert(s);
        byType.forEach((type, xs) -> xs.forEach(no -> {
            InvShipmentItem it = new InvShipmentItem();
            it.setShipmentNo(s.getShipmentNo());
            it.setItemType(type);
            it.setItemNo(no);
            items.insert(it);
        }));
        log.info("发货物流 shipmentNo={} docType={} docRef={} carrier={} trackingNo={} 台数={}", s.getShipmentNo(), docType, docRef,
                s.getCarrier(), s.getTrackingNo(), nos.size());
        return vo(s, nos);
    }

    @Override
    public List<Shipment> list(String docType, String docRef, String itemNo) {
        LambdaQueryWrapper<InvShipment> w = new LambdaQueryWrapper<InvShipment>().orderByDesc(InvShipment::getId).last("limit 200");
        if (!blank(docType)) w.eq(InvShipment::getDocType, docType.trim());
        if (!blank(docRef)) w.eq(InvShipment::getDocRef, docRef.trim());
        if (!blank(itemNo)) {
            List<String> nos = items.selectList(new LambdaQueryWrapper<InvShipmentItem>().eq(InvShipmentItem::getItemNo, itemNo.trim()))
                    .stream().map(InvShipmentItem::getShipmentNo).toList();
            if (nos.isEmpty()) return List.of();
            w.in(InvShipment::getShipmentNo, nos);
        }
        if (blank(docType) && blank(docRef) && blank(itemNo)) throw BizException.badRequest("error.common.missing_parameter", "docRef / itemNo");
        List<InvShipment> rows = shipments.selectList(w);
        if (rows.isEmpty()) return List.of();
        Map<String, List<String>> byNo = items.selectList(new LambdaQueryWrapper<InvShipmentItem>()
                        .in(InvShipmentItem::getShipmentNo, rows.stream().map(InvShipment::getShipmentNo).toList()).orderByAsc(InvShipmentItem::getId))
                .stream().collect(Collectors.groupingBy(InvShipmentItem::getShipmentNo, Collectors.mapping(InvShipmentItem::getItemNo, Collectors.toList())));
        return rows.stream().map(s -> vo(s, byNo.getOrDefault(s.getShipmentNo(), List.of()))).toList();
    }

    private static LocalDateTime shippedAt(ShipInfo i) {
        if (blank(i.shippedAt())) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(i.shippedAt().trim().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw BizException.badRequest("error.common.invalid_value", "shippedAt");
        }
    }

    private static Shipment vo(InvShipment s, List<String> nos) {
        return new Shipment(s.getShipmentNo(), s.getDocType(), s.getDocRef(), s.getCarrier(), s.getTrackingNo(), s.getShippedAt(),
                s.getNote(), nos, s.getCreatedBy());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }

    private static int len(String s) {
        return s == null ? 0 : s.trim().length();
    }
}
