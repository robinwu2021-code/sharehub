package ai.neargo.opskit.platform.api.account;

import java.util.Objects;

/**
 * 接口路径里的空间寻址：空间 ID（{@code ws_…}）或伙伴租户号（{@code ext:{ref}}，21/06 §二）。
 * 伙伴只知道自己的租户号，不必先查一次空间 ID。
 */
public sealed interface WorkspaceRef {

    String EXT_PREFIX = "ext:";

    record ById(String workspaceId) implements WorkspaceRef {
        public ById {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    record ByExternalRef(String externalRef) implements WorkspaceRef {
        public ByExternalRef {
            if (externalRef == null || externalRef.isBlank()) {
                throw new IllegalArgumentException("external ref must not be blank");
            }
        }
    }

    static WorkspaceRef parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("workspace ref must not be blank");
        }
        return text.startsWith(EXT_PREFIX) ? new ByExternalRef(text.substring(EXT_PREFIX.length())) : new ById(text);
    }
}
