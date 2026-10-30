package ai.neargo.sharehub.inv.service;

import ai.neargo.sharehub.api.platform.flow.*;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.inv.CountReviewState;
import ai.neargo.sharehub.inv.CountReviewDecision;
import ai.neargo.sharehub.inv.dto.StockDtos.CountReview;
import ai.neargo.sharehub.inv.entity.InvCountReview;
import ai.neargo.sharehub.inv.entity.InvWarehouse;
import ai.neargo.sharehub.inv.mapper.InvCountReviewMapper;
import ai.neargo.sharehub.inv.mapper.InvWarehouseMapper;
import ai.neargo.sharehub.inv.remote.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Unified approval owns decisions; this service only links a frozen count to that decision and resumes inventory execution. */
@Service
public class CountReviewService implements ApprovalOutcomeHandler {
    public static final String BIZ_TYPE="inventory_count", PROCESS="inventory_count_audit";
    private enum CountStatus { OPEN, SUBMITTED, APPROVED, POSTED, CANCELLED }
    private final InvCountReviewMapper rows;
    private final InvWarehouseMapper warehouses;
    private final RemoteInventoryPort remote;
    private final InventoryLinks links;
    private final DomainEventBus events;
    private final ApprovalAvailability approval;
    private final TransactionTemplate tx;

    public CountReviewService(InvCountReviewMapper rows,InvWarehouseMapper warehouses,RemoteInventoryPort remote,
                              InventoryLinks links,DomainEventBus events,ApprovalAvailability approval,PlatformTransactionManager transactions){
        this.rows=rows;this.warehouses=warehouses;this.remote=remote;this.links=links;this.events=events;this.approval=approval;
        tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public String bizType(){return BIZ_TYPE;}
    public CountReview view(String no){
        var r=rows.selectById(no);
        return r==null?new CountReview(CountReviewState.DRAFT.name(),null,0,null,null,null,null,approval.configured())
                :new CountReview(r.getExecutionState(),r.getApprovalNo(),r.getRoundNo(),r.getSubmitterNo(),r.getDecision(),r.getDeciderName(),r.getLastError(),approval.configured());
    }
    private InvCountReview lock(String no){return rows.selectOne(new LambdaQueryWrapper<InvCountReview>()
            .eq(InvCountReview::getCountNo,no).last("limit 1 for update"));}
    private static boolean is(InvCountReview r,CountReviewState state){return state.name().equals(r.getExecutionState());}
    private void state(InvCountReview r,CountReviewState state){r.setExecutionState(state.name());rows.updateById(r);}
    private InventoryApi.CountView count(InvCountReview r){
        if(!remote.api().configured()) throw BizException.conflict("error.inventory.not_configured");
        var v=remote.api().count(r.getCountNo());
        if(!r.getWarehouseNo().equals(links.warehouseNoOf(v.locationId()))) throw BizException.conflict("error.inventory.count_changed");
        return v;
    }
    private static void requireOpen(InventoryApi.CountView v){
        if(!CountStatus.OPEN.name().equals(v.status())) throw BizException.conflict("error.common.state_changed");
    }
    /** Serializes editing and submit on the same local count row, before any remote mutation. */
    public <T> T edit(String no,String warehouse,Supplier<T> action){
        return tx.execute(status->{
            rows.ensure(no,warehouse);var r=lock(no);
            if(!is(r,CountReviewState.DRAFT) && !(is(r,CountReviewState.APPLIED) && !CountReviewDecision.APPROVED.name().equals(r.getDecision())))
                throw BizException.conflict("error.inventory.unified_review_required");
            requireOpen(count(r));return action.get();
        });
    }
    public void submit(String no,String warehouse){
        if(!approval.configured()) throw BizException.conflict("error.inventory.approval_not_configured");
        var actor=SecurityUtils.requireUser();
        // Commit the original actor/version BEFORE remote submission. A lost reply never loses the recovery identity.
        tx.executeWithoutResult(status->{
            rows.ensure(no,warehouse);var r=lock(no);
            if(is(r,CountReviewState.SUBMITTING)||is(r,CountReviewState.IN_REVIEW)) return;
            if(is(r,CountReviewState.PREPARING)||is(r,CountReviewState.SUBMIT_FAILED)){
                if(!actor.userNo().equals(r.getSubmitterNo())) throw new AccessDeniedException("count submission owner");
                return;
            }
            if(!is(r,CountReviewState.DRAFT) && !(is(r,CountReviewState.APPLIED)&&!CountReviewDecision.APPROVED.name().equals(r.getDecision())))
                throw BizException.conflict("error.inventory.unified_review_required");
            var v=count(r);requireOpen(v);
            var wh=warehouses.selectOne(new LambdaQueryWrapper<InvWarehouse>().eq(InvWarehouse::getWarehouseNo,warehouse).last("limit 1"));
            if(wh==null||wh.getArchivedAt()!=null) throw BizException.conflict("error.common.state_changed");
            rows.update(null,new LambdaUpdateWrapper<InvCountReview>().eq(InvCountReview::getCountNo,no)
                    .set(InvCountReview::getApprovalNo,null).set(InvCountReview::getInstanceNo,null).set(InvCountReview::getDecision,null)
                    .set(InvCountReview::getDeciderNo,null).set(InvCountReview::getDeciderName,null).set(InvCountReview::getDecisionNote,null)
                    .set(InvCountReview::getLastError,null));
            r.setApprovalNo(null);r.setInstanceNo(null);r.setDecision(null);r.setDeciderNo(null);r.setDeciderName(null);r.setDecisionNote(null);r.setLastError(null);
            r.setRoundNo(r.getRoundNo()+1);r.setContentVersion(v.contentVersion());r.setContentHash(hash(v));
            r.setSubmitterNo(actor.userNo());r.setSubmitterName(actor.username());r.setScopeRegionId(wh.getRegionId());
            state(r,CountReviewState.PREPARING);
        });
        tx.executeWithoutResult(status->{
            var r=lock(no);
            if(is(r,CountReviewState.SUBMITTING)||is(r,CountReviewState.IN_REVIEW)) return;
            if(!is(r,CountReviewState.PREPARING)&&!is(r,CountReviewState.SUBMIT_FAILED)) throw BizException.conflict("error.common.state_changed");
            var v=count(r);unchanged(r,v);
            if(CountStatus.OPEN.name().equals(v.status())) remote.api().submitCount(no,key(r,"submit"));
            v=count(r);unchanged(r,v);
            if(!CountStatus.SUBMITTED.name().equals(v.status())) throw BizException.conflict("error.common.state_changed");
            state(r,CountReviewState.SUBMITTING);
            Map<String,Object> fields=new LinkedHashMap<>();fields.put("countNo",no);fields.put("warehouseNo",r.getWarehouseNo());
            fields.put("contentVersion",r.getContentVersion());fields.put("round",r.getRoundNo());
            fields.put("differenceQty",v.lines()==null?0:v.lines().stream().mapToInt(l->l.diffQty()==null?0:Math.abs(l.diffQty())).sum());
            List<String> scopes=r.getScopeRegionId()==null?List.of():List.of("region:"+r.getScopeRegionId());
            events.publish(new ApprovalRequestedEvent(BIZ_TYPE,no,r.getRoundNo(),PROCESS,"盘点差异复核 · "+r.getWarehouseNo()+" · "+no,
                    fields,scopes,r.getSubmitterNo(),r.getSubmitterName()));
        });
    }
    @Override public void onSubmitted(String no,int round,String instance,String approvalNo){
        if(approvalNo==null||approvalNo.isBlank()||instance==null||instance.isBlank())
            throw BizException.conflict("error.common.state_changed");
        tx.executeWithoutResult(status->{var r=lock(no);
            if(r==null||!Objects.equals(r.getRoundNo(),round)||!is(r,CountReviewState.SUBMITTING)) return;
            r.setApprovalNo(approvalNo);r.setInstanceNo(instance);r.setLastError(null);state(r,CountReviewState.IN_REVIEW);
            rows.update(null,new LambdaUpdateWrapper<InvCountReview>().eq(InvCountReview::getCountNo,no).set(InvCountReview::getLastError,null));
        });
    }
    @Override public void onSubmitFailed(String no,int round,String error){
        tx.executeWithoutResult(status->{var r=lock(no);
            if(r==null||!Objects.equals(r.getRoundNo(),round)||!is(r,CountReviewState.SUBMITTING)) return;
            r.setLastError(shortError(error));state(r,CountReviewState.SUBMIT_FAILED);
        });
    }
    @Override public void onCompleted(String no,ApprovalDecision decision){
        Boolean accepted=tx.execute(status->{
            var r=lock(no);if(r==null||is(r,CountReviewState.APPLIED)) return false;
            if(r.getApprovalNo()==null) throw BizException.conflict("error.common.state_changed"); // wait for send acknowledgement; webhook is retried
            if(!r.getApprovalNo().equals(decision.approvalNo())) return false;
            CountReviewDecision result=CountReviewDecision.valueOf(decision.result());
            if(decision.deciderNo()==null||decision.deciderNo().isBlank()) throw BizException.badRequest("error.common.missing_parameter","deciderNo");
            boolean self=decision.deciderNo().equals(r.getSubmitterNo());
            if(result==CountReviewDecision.WITHDRAWN?!self:self) throw new AccessDeniedException("count four eyes");
            if(is(r,CountReviewState.DECIDED)||is(r,CountReviewState.APPLY_FAILED)) return result.name().equals(r.getDecision());
            if(!is(r,CountReviewState.IN_REVIEW)) return false;
            r.setDecision(result.name());r.setDeciderNo(decision.deciderNo());r.setDeciderName(decision.deciderName());r.setDecisionNote(decision.reason()==null?null:decision.reason().substring(0,Math.min(decision.reason().length(),512)));
            state(r,CountReviewState.DECIDED);return true;
        });
        if(Boolean.TRUE.equals(accepted)) apply(no);
    }
    /** Operator can resume only an already authorized decision; this endpoint cannot create an approval. */
    public void retry(String no){
        var r=rows.selectById(no);
        if(r==null||r.getDecision()==null||(!is(r,CountReviewState.DECIDED)&&!is(r,CountReviewState.APPLY_FAILED)&&!is(r,CountReviewState.APPLIED)))
            throw BizException.conflict("error.inventory.unified_review_required");
        apply(no);
    }
    private void apply(String no){
        try {
            tx.executeWithoutResult(status->{
                var r=lock(no);if(is(r,CountReviewState.APPLIED)) return;
                if(!is(r,CountReviewState.DECIDED)&&!is(r,CountReviewState.APPLY_FAILED)) throw BizException.conflict("error.common.state_changed");
                InventoryActor.asStaff(r.getDeciderNo(),()->{
                var v=count(r);unchanged(r,v);
                if(CountReviewDecision.APPROVED.name().equals(r.getDecision())){
                    if(CountStatus.SUBMITTED.name().equals(v.status())) remote.api().approveCount(no,r.getDecisionNote(),key(r,"approve"));
                    v=count(r);unchanged(r,v);
                    if(!Objects.equals(r.getContentVersion(),v.approvedVersion())) throw BizException.conflict("error.inventory.count_changed");
                    if(CountStatus.APPROVED.name().equals(v.status())){
                        var posted=remote.api().postCount(no,key(r,"post"));r.setGainInboundNo(posted.gainInboundNo());r.setLossOutboundNo(posted.lossOutboundNo());
                    }
                    if(!CountStatus.POSTED.name().equals(count(r).status())) throw BizException.conflict("error.common.state_changed");
                } else {
                    if(CountStatus.SUBMITTED.name().equals(v.status())) remote.api().rejectCount(no,r.getDecisionNote()==null?r.getDecision():r.getDecisionNote(),key(r,"reject"));
                    requireOpen(count(r));
                }
                r.setLastError(null);state(r,CountReviewState.APPLIED);
                rows.update(null,new LambdaUpdateWrapper<InvCountReview>().eq(InvCountReview::getCountNo,no).set(InvCountReview::getLastError,null));
                return null;});
            });
        } catch(RuntimeException error){
            tx.executeWithoutResult(status->{var r=lock(no);if(r!=null&&(is(r,CountReviewState.DECIDED)||is(r,CountReviewState.APPLY_FAILED))){
                r.setLastError(shortError(error instanceof InventoryRemoteException remoteError?remoteError.code:error.getMessage()));state(r,CountReviewState.APPLY_FAILED);
            }});
        }
    }
    private static String key(InvCountReview r,String action){
        return "count-"+sha256(r.getCountNo()+":"+r.getRoundNo()+":"+action).substring(0,58);
    }
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("Required JDK SHA-256 unavailable",error);}
    }
    private static void unchanged(InvCountReview r,InventoryApi.CountView v){
        if(!Objects.equals(r.getContentVersion(),v.contentVersion())||!Objects.equals(r.getContentHash(),hash(v)))
            throw BizException.conflict("error.inventory.count_changed");
    }
    private static String hash(InventoryApi.CountView v){
        // Sort independent collections; don't depend on remote row ordering.
        var lines=v.lines()==null?List.of():v.lines().stream().sorted(Comparator.comparing(InventoryApi.CountLine::itemId)).toList();
        var extra=v.exceptions()==null?List.of():v.exceptions().stream().sorted(Comparator.comparing(InventoryApi.CountScan::serialNo).thenComparing(InventoryApi.CountScan::verdict)).toList();
        var missing=v.missingSerials()==null?List.of():v.missingSerials().stream().sorted().toList();
        return sha256(Json.write(List.of(v.locationId(),v.contentVersion(),lines,extra,missing)));
    }
    private static String shortError(String message){return message==null?null:message.substring(0,Math.min(message.length(),1024));}
}
