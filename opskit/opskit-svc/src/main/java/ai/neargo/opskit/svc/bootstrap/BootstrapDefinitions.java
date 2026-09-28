package ai.neargo.opskit.svc.bootstrap;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.core.app.ProcessDefinitionAppService;
import ai.neargo.opskit.process.core.app.SlaPolicyAppService;
import ai.neargo.opskit.process.store.ProcessDefCodec;
import ai.neargo.opskit.process.store.SlaPolicyCodec;
import ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService;
import ai.neargo.opskit.ticket.store.TicketDefCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动引导的定义段：把部署方目录里的流程 / SLA / 工单类型按代号幂等发布（与角色段同为「部署方数据」）。
 *
 * <p>顺序固定为 option-sets → processes → sla-policies → ticket-types（工单类型校验时要引用前三者）。
 * 文件名须与定义代号一致（repair_flow.json ↔ "code": "repair_flow"），避免改了文件却发布到另一个代号上。
 * 任何一个文件不合法都拒绝启动，并指出文件与字段 —— 起来了却少一个类型，比起不来更难查。
 */
public class BootstrapDefinitions {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDefinitions.class);
    private static final String CHANNEL = "API";

    /** @param published 本次出了新版本的；unchanged 内容未变、沿用现有版本的 */
    public record Summary(List<String> published, List<String> unchanged) {
        public static final Summary NONE = new Summary(List.of(), List.of());

        @Override
        public String toString() {
            return "published=" + published + " unchanged=" + unchanged;
        }
    }

    private final ProcessDefinitionAppService processDefs;
    private final SlaPolicyAppService slaPolicies;
    private final TicketDefinitionAppService ticketDefs;

    public BootstrapDefinitions(ProcessDefinitionAppService processDefs, SlaPolicyAppService slaPolicies, TicketDefinitionAppService ticketDefs) {
        this.processDefs = processDefs;
        this.slaPolicies = slaPolicies;
        this.ticketDefs = ticketDefs;
    }

    public Summary publishAll(Actor system, WorkspaceId ws, Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("opskit.bootstrap.definitions-dir 不是目录：" + dir.toAbsolutePath());
        }
        List<String> published = new java.util.ArrayList<>();
        List<String> unchanged = new java.util.ArrayList<>();
        for (Path f : files(dir.resolve("option-sets"))) {
            var d = parse(f, TicketDefCodec::optionSetFromJson);
            requireNamedAfter(f, d.code());
            record(f, "option-set:" + d.code(), publishing(f, () -> ticketDefs.publishOptionSet(system, ws, d, note(f), CHANNEL).created()), published, unchanged);
        }
        for (Path f : files(dir.resolve("processes"))) {
            var d = parse(f, ProcessDefCodec::fromJson);
            requireNamedAfter(f, d.code());
            record(f, "process:" + d.code(), publishing(f, () -> processDefs.publish(system, ws, d, note(f), CHANNEL).created()), published, unchanged);
        }
        for (Path f : files(dir.resolve("sla-policies"))) {
            var d = parse(f, SlaPolicyCodec::fromJson);
            requireNamedAfter(f, d.code());
            record(f, "sla:" + d.code(), publishing(f, () -> slaPolicies.publish(system, ws, d, note(f), CHANNEL).created()), published, unchanged);
        }
        for (Path f : files(dir.resolve("ticket-types"))) {
            var d = parse(f, TicketDefCodec::typeFromJson);
            requireNamedAfter(f, d.code());
            record(f, "ticket-type:" + d.code(), publishing(f, () -> ticketDefs.publishType(system, ws, d, note(f), CHANNEL).created()), published, unchanged);
        }
        return new Summary(List.copyOf(published), List.copyOf(unchanged));
    }

    /** 校验不通过时带上文件名与逐字段错误，运维照着改文件即可。 */
    private static boolean publishing(Path f, java.util.function.BooleanSupplier publish) {
        try {
            return publish.getAsBoolean();
        } catch (OpsKitException e) {
            throw new IllegalStateException("定义不合法 " + f + "：" + e.errorKey().key() + " " + e.fieldErrors(), e);
        }
    }

    private static void record(Path f, String what, boolean created, List<String> published, List<String> unchanged) {
        if (created) {
            published.add(what);
            log.info("启动引导发布定义新版本 {} file={}", what, f.getFileName());
        } else {
            unchanged.add(what);
        }
    }

    private static List<Path> files(Path sub) {
        if (!Files.isDirectory(sub)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(sub)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T parse(Path f, java.util.function.Function<String, T> codec) {
        String json;
        try {
            json = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不了定义文件 " + f, e);
        }
        try {
            return codec.apply(json);
        } catch (OpsKitException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("定义文件格式不对 " + f + "：" + e.getMessage(), e);
        }
    }

    private static void requireNamedAfter(Path f, String code) {
        String stem = f.getFileName().toString().replaceFirst("\\.json$", "");
        if (!stem.equals(code)) {
            throw new IllegalStateException("定义文件名须与代号一致：" + f + " 里的 code 是 " + code);
        }
    }

    private static String note(Path f) {
        return "bootstrap:" + f.getFileName();
    }
}
