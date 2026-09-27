package ai.neargo.opskit.platform.api;

import ai.neargo.opskit.commons.error.ErrorKey;

/** 平台模块的错误键（通用的在 CommonErrors）。 */
public final class PlatformErrors {

    public static final ErrorKey WORKSPACE_REF_TAKEN = new ErrorKey("opskit.platform.workspace_ref_taken", 409);
    public static final ErrorKey TENANT_INACTIVE = new ErrorKey("opskit.platform.tenant_inactive", 403);

    private PlatformErrors() {
    }
}
