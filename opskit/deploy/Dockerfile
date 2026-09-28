# OpsKit 运行镜像（21/07 O4-4）。只装已构建好的 jar —— 构建在镜像外做（scripts/build-image.sh），
# 这样镜像里的 jar 与 scripts/verify.sh 验过的是同一个产物，也不用在镜像里访问私仓。
FROM eclipse-temurin:21-jre

# 非 root 运行；/data/log 给 OPSKIT_LOG_DIR 用（设了才落盘）
RUN groupadd --system opskit && useradd --system --gid opskit --home-dir /opt/opskit --shell /usr/sbin/nologin opskit \
    && mkdir -p /opt/opskit /etc/opskit /data/log/opskit && chown opskit:opskit /data/log/opskit
COPY --chown=opskit:opskit opskit-svc.jar /opt/opskit/opskit-svc.jar

USER opskit
WORKDIR /opt/opskit
EXPOSE 8090
# 容器按 cgroup 内存上限定堆；其余 JVM 参数用 JAVA_OPTS 追加
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/opskit/opskit-svc.jar --spring.profiles.active=prod \"$@\"", "--"]
