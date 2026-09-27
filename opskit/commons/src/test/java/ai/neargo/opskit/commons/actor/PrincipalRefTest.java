package ai.neargo.opskit.commons.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrincipalRefTest {

    @Test
    void roundTripsThroughItsTextForm() {
        for (String text : new String[] {"member:mb_01j8", "group:field-ops", "perm:location:contract:audit", "participant:OWNER"}) {
            assertThat(PrincipalRef.parse(text).key()).isEqualTo(text);
        }
        // 权限码里本身带冒号：只按第一个冒号切
        assertThat(PrincipalRef.parse("perm:location:contract:audit").id()).isEqualTo("location:contract:audit");
    }

    @Test
    void rejectsMalformedText() {
        assertThatThrownBy(() -> PrincipalRef.parse("nobody")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrincipalRef.parse("robot:x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrincipalRef.parse("member:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrincipalRef.parse("member:a b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void candidateKeysCoverEveryKindOfEligibility() {
        Actor actor = new Actor(new TenantContext(new AccountId("ac_1"), new WorkspaceId("ws_1")),
                Optional.of(PrincipalRef.member("mb_1")), Optional.empty(),
                Set.of("field-ops"), Set.of("approver"), Set.of("location:contract:audit"), Set.of(), Set.of());
        assertThat(CandidateKeys.of(actor)).containsExactlyInAnyOrder(
                "member:mb_1", "group:field-ops", "role:approver", "perm:location:contract:audit");
    }

    @Test
    void actorNeedsAMemberOrAnApp() {
        TenantContext t = new TenantContext(new AccountId("ac_1"), new WorkspaceId("ws_1"));
        assertThatThrownBy(() -> new Actor(t, Optional.empty(), Optional.empty(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(t, Optional.of(PrincipalRef.group("g")), Optional.empty(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
