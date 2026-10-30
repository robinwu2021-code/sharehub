package ai.neargo.sharehub.inv;

import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.api.platform.port.LocationQueryPort;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.sharehub.inv.entity.InvTransfer;
import ai.neargo.sharehub.inv.mapper.InvTransferMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 调拨读取按收发任一方，单头修改按双方，发货／签收分别按责任端授权。 */
@Component
public class TransferAccess {
    private final WarehouseDirectory warehouses;
    private final SiteQueryPort sites;
    private final LocationQueryPort locations;
    private final InvTransferMapper transfers;
    public TransferAccess(WarehouseDirectory warehouses, SiteQueryPort sites, LocationQueryPort locations, InvTransferMapper transfers) {
        this.warehouses=warehouses; this.sites=sites; this.locations=locations; this.transfers=transfers;
    }
    private boolean restricted() { var s=StaffContext.dataScope(); return s!=null && !s.all(); }
    private boolean can(String type,String ref) {
        if (!restricted()) return true;
        if (ref==null) return false;
        if (TransferEndpointType.WAREHOUSE.name().equals(type)) return warehouses.visibleWarehouseNos().contains(ref);
        if (TransferEndpointType.SITE.name().equals(type)) return !sites.briefsByNos(List.of(ref)).isEmpty();
        if (TransferEndpointType.LOCATION.name().equals(type)) {
            // 只解引用点位归属；授权仍必须由下面的 scoped site 查询决定。
            var owner=ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(()->locations.ownershipOf(ref));
            return owner!=null && owner.siteNo()!=null && !sites.briefsByNos(List.of(owner.siteNo())).isEmpty();
        }
        return false;
    }
    public void read(InvTransfer t) {
        if (!can(t.getFromType(),t.getFromRef()) && !can(t.getToType(),t.getToRef())) deny();
    }
    public void edit(InvTransfer t) { source(t); target(t); }
    public void source(InvTransfer t) { if (!can(t.getFromType(),t.getFromRef())) deny(); }
    public void target(InvTransfer t) { if (!can(t.getToType(),t.getToRef())) deny(); }
    private static void deny() { throw new AccessDeniedException("inventory transfer endpoint scope"); }

    /** 在数据库分页前筛选。站点走批量Port，点位只取单据实际引用的集合，不复制外部归属表。 */
    public void filter(LambdaQueryWrapper<InvTransfer> query) {
        if (!restricted()) return;
        Map<String,LinkedHashSet<String>> referenced=new LinkedHashMap<>();
        for (InvTransfer t:transfers.selectList(new LambdaQueryWrapper<InvTransfer>()
                .select(InvTransfer::getFromType,InvTransfer::getFromRef,InvTransfer::getToType,InvTransfer::getToRef))) {
            if(t.getFromRef()!=null) referenced.computeIfAbsent(t.getFromType(),k->new LinkedHashSet<>()).add(t.getFromRef());
            if(t.getToRef()!=null) referenced.computeIfAbsent(t.getToType(),k->new LinkedHashSet<>()).add(t.getToRef());
        }
        Map<String,List<String>> allowed=new LinkedHashMap<>();
        allowed.put(TransferEndpointType.WAREHOUSE.name(),warehouses.visibleWarehouseNos());
        List<String> siteRefs=new ArrayList<>(referenced.getOrDefault(TransferEndpointType.SITE.name(),new LinkedHashSet<>()));
        allowed.put(TransferEndpointType.SITE.name(),sites.briefsByNos(siteRefs).stream().map(s->s.siteNo()).toList());
        List<String> locationRefs=new ArrayList<>();
        for(String ref:referenced.getOrDefault(TransferEndpointType.LOCATION.name(),new LinkedHashSet<>()))
            if(can(TransferEndpointType.LOCATION.name(),ref)) locationRefs.add(ref);
        allowed.put(TransferEndpointType.LOCATION.name(),locationRefs);
        allowed.entrySet().removeIf(e->e.getValue().isEmpty());
        if(allowed.isEmpty()){query.apply("1=0");return;}
        query.and(q->{
            boolean first=true;
            for(var e:allowed.entrySet()) {
                if(!first) q.or();
                q.nested(x->x.eq(InvTransfer::getFromType,e.getKey()).in(InvTransfer::getFromRef,e.getValue()))
                        .or(x->x.eq(InvTransfer::getToType,e.getKey()).in(InvTransfer::getToRef,e.getValue()));
                first=false;
            }
        });
    }
}
