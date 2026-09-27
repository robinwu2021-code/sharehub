package ai.neargo.opskit.process.core.app.archfixture;

import ai.neargo.opskit.commons.security.RequiresPermission;

/** 违反 A12：一个没声明权限的用例，一个用了目录外权限码的用例。 */
public class UnguardedAppService {
    public void forgotToDeclare() {
    }

    @RequiresPermission("no:such:permission")
    public void unknownCode() {
    }
}
