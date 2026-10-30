package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import ai.neargo.sharehub.inv.remote.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

/** Only external OpsKit/inventory boundaries are simulated; real API, SQL, outbox, signing and callback routing. */
class InventoryUnifiedApprovalTest extends ApiTestSupport {
    static final String SECRET="inventory-review-test";
    static final ObjectMapper JSON=new ObjectMapper();
    static final List<Map<String,Object>> SENT=new CopyOnWriteArrayList<>();
    static final List<Map<String,String>> WIRES=new CopyOnWriteArrayList<>();
    static boolean omitApproval;
    static final HttpServer SERVER;
    static {
        try {
            SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            SERVER.createContext("/api/opskit/v1/workspaces/",ex->{
                var data=new HashMap<String,Object>(JSON.readValue(ex.getRequestBody().readAllBytes(),Map.class));
                data.put("idem",ex.getRequestHeaders().getFirst("Idempotency-Key"));SENT.add(data);
                String ap="AP-INV-"+UUID.randomUUID();data.put("approvalNo",ap);
                byte[] out=JSON.writeValueAsBytes(omitApproval?Map.of("instanceNo","PI-"+ap):Map.of("instanceNo","PI-"+ap,"approvalNo",ap));
                ex.getResponseHeaders().add("Content-Type","application/json");ex.sendResponseHeaders(200,out.length);
                ex.getResponseBody().write(out);ex.close();
            });
            SERVER.createContext("/api/inventory/v1",ex->{
                WIRES.add(Map.of("actor",ex.getRequestHeaders().getFirst("X-Inventory-Actor"),"key",ex.getRequestHeaders().getFirst("Idempotency-Key"),"path",ex.getRequestURI().getPath()));
                ex.getRequestBody().readAllBytes();byte[] out="{}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type","application/json");ex.sendResponseHeaders(200,out.length);ex.getResponseBody().write(out);ex.close();
            });SERVER.start();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("sharehub.opskit.base-url",()->"http://127.0.0.1:"+SERVER.getAddress().getPort());
        r.add("sharehub.opskit.workspace",()->"ext:MAIN");r.add("sharehub.opskit.app-token",()->"okk_inventory");
        r.add("sharehub.opskit.webhook-secret",()->SECRET);
    }
    @AfterAll static void stop(){SERVER.stop(0);}
    @Autowired JdbcTemplate jdbc;
    @Autowired RemoteInventoryPort remote;
    @Autowired HttpInventoryApi http;
    AuditInventory fake;
    String admin,staff,count,wh;
    static class AuditInventory extends InventoryFake {
        int approvals,posts; boolean losePostReply,failBeforeApprove,wire; int version=1;
        final HttpInventoryApi client=new HttpInventoryApi(new InventoryEndpoint(){
            public boolean configured(){return true;} public String baseUrl(){return "http://127.0.0.1:"+SERVER.getAddress().getPort();}
            public String mode(){return "STATIC";}
        },"test","test",java.time.Duration.ofSeconds(1),java.time.Duration.ofSeconds(1));
        @Override public void submitCount(String no,String key){if(wire)client.submitCount(no,key);super.submitCount(no,key);}
        @Override public CountView count(String no){var v=super.count(no);return new CountView(v.countNo(),v.locationId(),v.status(),version,
                List.of("APPROVED","POSTED").contains(v.status())?version:null,v.submittedBy(),v.reviewer(),v.lines(),v.exceptions(),v.missingSerials());}
        @Override public void approveCount(String no,String note,String key){if(failBeforeApprove){failBeforeApprove=false;throw new InventoryRemoteException(503,"inventory.unreachable",List.of(),"before approve",null);}
            if(wire)client.approveCount(no,note,key);approvals++;super.approveCount(no,note,key);}
        @Override public CountPosted postCount(String no,String key){if(wire)client.postCount(no,key);posts++;var r=super.postCount(no,key);
            if(losePostReply){losePostReply=false;throw new InventoryRemoteException(503,"inventory.unreachable",List.of(),"lost reply",null);}return r;}
    }
    @BeforeEach void setup(){
        SENT.clear();WIRES.clear();omitApproval=false;fake=new AuditInventory();remote.useApi(fake);
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");jdbc.update("DELETE FROM inventory_sync_state");
        String tag=Long.toUnsignedString(System.nanoTime(),36);wh="WHUA"+tag;
        jdbc.update("INSERT INTO inv_warehouse(warehouse_no,name,region_id) VALUES (?,?,'INV-AUDIT')",wh,wh);
        admin=login("ADMIN");staff=login("WAREHOUSE","COUNT-"+tag,null);
        count=post("/api/ops/inventory/counts",Map.of("warehouseNo",wh),staff).okData().path("count").path("countNo").asText();
    }
    @AfterEach void cleanup(){
        // New review rows are retained only while this test is running.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='inv_count_review'",Boolean.class)))
            jdbc.update("DELETE FROM inv_count_review WHERE count_no=?",count);
        jdbc.update("DELETE FROM sys_outbox WHERE aggregate_id=? AND aggregate_type='Approval:inventory_count'",count);
        jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no=?",wh);jdbc.update("DELETE FROM inventory_sync_state");
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");remote.useApi(http);
    }
    String submit(){
        post("/api/ops/inventory/counts/"+count+"/submit",Map.of(),staff).okData();
        assertThat(SENT).hasSize(1);return (String)SENT.getFirst().get("approvalNo");
    }
    Resp decide(String approval,String result,String decider,String event) throws Exception {
        var data=new HashMap<String,Object>();data.put("approvalNo",approval);data.put("result",result);data.put("reason","review decision");
        data.put("decidedBy",Map.of("externalRef",decider,"displayName",decider));data.put("subject",Map.of("type","inventory_count","ref",count));
        String body=JSON.writeValueAsString(Map.of("eventType","approval.completed","idemKey",event,"data",data));long t=System.currentTimeMillis();
        return sendRequest(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/opskit/webhook"))
                .header("Content-Type","application/json").header("X-OpsKit-Signature","t="+t+",v1="+OpsKitWithdrawalApprovalTest.hmac(SECRET,t+"."+body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }
    @Test void submissionIsVisibleToUnifiedApprovalAndCannotBeRepeated(){
        String ap=submit();assertThat(SENT.getFirst().get("idem")).isEqualTo("opskit:inventory_count:"+count+":1");
        assertThat(SENT.getFirst().get("processCode")).isEqualTo("inventory_count_audit");
        var detail=get("/api/ops/inventory/counts/"+count,staff).okData();
        assertThat(detail.path("review").path("approvalNo").asText()).isEqualTo(ap);
        post("/api/ops/inventory/counts/"+count+"/submit",Map.of(),staff).okData();assertThat(SENT).hasSize(1);
        assertThat(fake.count(count).status()).isEqualTo("SUBMITTED");assertThat(fake.posts).isZero();
    }
    @Test void directApprovalAndPostingAreBlockedEvenForAdmin(){
        fake.counts.get(count)[1]="SUBMITTED";
        assertThat(post("/api/ops/inventory/counts/"+count+"/approve",Map.of(),admin).status).isEqualTo(409);
        fake.counts.get(count)[1]="APPROVED";
        assertThat(post("/api/ops/inventory/counts/"+count+"/post",Map.of(),admin).status).isEqualTo(409);
        assertThat(fake.approvals).isZero();assertThat(fake.posts).isZero();
    }
    @Test void matchingApprovedCallbackPostsExactlyOnce() throws Exception {
        String ap=submit();assertThat(decide(ap,"APPROVED","reviewer.other",ap+":1").status).isEqualTo(200);
        assertThat(decide(ap,"APPROVED","reviewer.other",ap+":1").status).isEqualTo(200);
        assertThat(fake.count(count).status()).isEqualTo("POSTED");assertThat(fake.approvals).isEqualTo(1);assertThat(fake.posts).isEqualTo(1);
        assertThat(get("/api/ops/inventory/counts/"+count,staff).okData().path("review").path("state").asText()).isEqualTo("APPLIED");
    }
    @Test void selfApprovalAndWrongApprovalCannotChangeInventory() throws Exception {
        String ap=submit();String actor=get("/api/ops/inventory/counts/"+count,staff).okData().path("review").path("submitterNo").asText();
        assertThat(decide(ap,"APPROVED",actor,ap+":self").status).isEqualTo(500);
        assertThat(decide("OLD-"+ap,"APPROVED","reviewer.other",ap+":old").status).isEqualTo(200);
        assertThat(fake.approvals).isZero();assertThat(fake.posts).isZero();
    }
    @Test void changedContentIsBlockedAndFailureRemainsVisible() throws Exception {
        String ap=submit();fake.version++;
        assertThat(decide(ap,"APPROVED","reviewer.other",ap+":1").status).isEqualTo(200);
        assertThat(fake.approvals).isZero();assertThat(fake.posts).isZero();
        assertThat(get("/api/ops/inventory/counts/"+count,staff).okData().path("review").path("state").asText()).isEqualTo("APPLY_FAILED");
    }
    @Test void lostPostReplyIsRecoveredWithoutPostingTwice() throws Exception {
        String ap=submit();fake.losePostReply=true;assertThat(decide(ap,"APPROVED","reviewer.other",ap+":1").status).isEqualTo(200);
        post("/api/ops/inventory/counts/"+count+"/post",Map.of(),staff).okData();
        assertThat(fake.count(count).status()).isEqualTo("POSTED");assertThat(fake.posts).isEqualTo(1);
    }
    @Test void rejectionReturnsToEditingAndResubmissionUsesNewRound() throws Exception {
        String ap=submit();assertThat(decide(ap,"REJECTED","reviewer.other",ap+":1").status).isEqualTo(200);assertThat(fake.count(count).status()).isEqualTo("OPEN");
        post("/api/ops/inventory/counts/"+count+"/submit",Map.of(),staff).okData();
        assertThat(SENT).hasSize(2);assertThat(SENT.get(1).get("idem")).isEqualTo("opskit:inventory_count:"+count+":2");
        assertThat(decide(ap,"APPROVED","reviewer.other",ap+":late").status).isEqualTo(200);assertThat(fake.posts).isZero();
    }
    @Test void longCountNumbersKeepDistinctBoundedWireKeys() throws Exception {
        var old=fake.counts.remove(count);count="COUNT-"+"x".repeat(58);fake.counts.put(count,old);fake.wire=true;
        String ap=submit();assertThat(decide(ap,"APPROVED","reviewer.other",ap+":1").status).isEqualTo(200);
        assertThat(WIRES.stream().map(x->x.get("key")).toList()).hasSize(3).doesNotHaveDuplicates().allMatch(k->k.length()<=64);
    }
    @Test void retryUsesTheAuthorizedReviewerInsteadOfTheRetryingSubmitter() throws Exception {
        fake.wire=true;String ap=submit();fake.failBeforeApprove=true;
        assertThat(decide(ap,"APPROVED","reviewer.other",ap+":1").status).isEqualTo(200);
        post("/api/ops/inventory/counts/"+count+"/post",Map.of(),staff).okData();
        assertThat(WIRES.stream().filter(x->x.get("path").endsWith("/approve")).map(x->x.get("actor")).toList())
                .containsExactly("STAFF:reviewer.other");
    }
    @Test void missingApprovalAcknowledgementRemainsRetryableInsteadOfInReview() {
        omitApproval=true;
        post("/api/ops/inventory/counts/"+count+"/submit",Map.of(),staff).okData();
        assertThat(get("/api/ops/inventory/counts/"+count,staff).okData().path("review").path("state").asText()).isEqualTo("SUBMITTING");
    }

    @Test void submittedCountCannotBeEditedOrCancelledBeforeUnifiedDecision() {
        submit();
        assertThat(post("/api/ops/inventory/counts/"+count+"/scans",Map.of("serialNos",List.of("CHANGED")),staff).status).isEqualTo(409);
        assertThat(post("/api/ops/inventory/counts/"+count+"/cancel",Map.of(),staff).status).isEqualTo(409);
        assertThat(fake.count(count).status()).isEqualTo("SUBMITTED");
    }
    @Test void executionPinsOriginalInventoryEngineAndRestoresHttpActorAfterFailure() throws Exception {
        String ap=submit();jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        assertThat(decide(ap,"APPROVED","reviewer.other",ap+":1").status).isEqualTo(200);
        assertThat(fake.count(count).status()).isEqualTo("POSTED");
        try {InventoryActor.asStaff("reviewer",()->{fake.client.approveCount("actor-test",null,"actor-test-1");throw new IllegalArgumentException("test");});}
        catch(IllegalArgumentException expected){}
        fake.client.submitCount("actor-test","actor-test-2");
        assertThat(WIRES.get(WIRES.size()-2).get("actor")).isEqualTo("STAFF:reviewer");
        assertThat(WIRES.getLast().get("actor")).isEqualTo("SYSTEM");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"execution_state","decision"})
    void internalVocabularyAlsoRejectsInvalidDirectDatabaseWrites(String column) {
        submit();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("UPDATE inv_count_review SET "+column+"='INVALID' WHERE count_no=?",count))
                // MySQL 报 3819（CHECK 违反），Spring 归不进 DataIntegrityViolation —— 按约束名断言
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("chk_count_review_");
    }

}
