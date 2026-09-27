package ai.neargo.opskit.svc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** OpsKit 进程：装配平台、流程、工单三个能力模块（21/02 §三）。 */
@SpringBootApplication
public class OpsKitApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsKitApplication.class, args);
    }
}
