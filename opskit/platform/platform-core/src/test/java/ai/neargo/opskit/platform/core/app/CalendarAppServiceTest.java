package ai.neargo.opskit.platform.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.calendar.CalendarDef;
import ai.neargo.opskit.platform.api.calendar.WorkingSlot;
import ai.neargo.opskit.platform.core.support.DirectoryEnv;
import ai.neargo.opskit.platform.store.dao.CalendarDao;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CalendarAppServiceTest {

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void workspaceCalendarOverridesTheTemplateAndRoundTrips(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        CalendarAppService svc = new CalendarAppService(new CalendarDao(e.f.jdbc, db.dialect()), e.f.clock, e.f.tx, e.audit);
        CalendarDef template = new CalendarDef("biz", "UTC", List.of(WorkingSlot.parse(DayOfWeek.MONDAY, "09:00", "18:00")), Set.of());
        CalendarDef own = new CalendarDef("biz", "Asia/Dubai",
                List.of(WorkingSlot.parse(DayOfWeek.MONDAY, "08:00", "24:00"), WorkingSlot.parse(DayOfWeek.TUESDAY, "00:00", "02:00")),
                Set.of(LocalDate.of(2026, 12, 2)));

        svc.upsert(e.system(), WorkspaceId.TEMPLATE, template);
        assertThat(svc.get(e.tenant, "biz")).isEqualTo(template);
        svc.upsert(e.system(), e.workspace, own);
        assertThat(svc.get(e.tenant, "biz")).isEqualTo(own);

        assertThatThrownBy(() -> svc.get(e.tenant, "missing"))
                .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.NOT_FOUND);
        assertThatThrownBy(() -> svc.upsert(e.system(), e.workspace, new CalendarDef("bad", "UTC", List.of(), Set.of())))
                .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);
    }
}
