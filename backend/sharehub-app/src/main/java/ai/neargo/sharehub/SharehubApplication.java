package ai.neargo.sharehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * powerbank 合并模块化单体入口（ADR-001）。
 *
 * <p>骨架阶段：内存种子、零基础设施启动，仅为点亮 ops-web 真实后端切换。
 * 真业务域落地时逐步引入 neargo-common-{data,security} 与持久层/鉴权。
 */
@SpringBootApplication
public class SharehubApplication {

    public static void main(String[] args) {
        SpringApplication.run(SharehubApplication.class, args);
    }
}
