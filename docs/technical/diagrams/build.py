# -*- coding: utf-8 -*-
"""各图的内容 spec。改架构只改这里，样式在 gen.py。"""
from gen import Svg, S, W, H, check, tw

OUT = "."

# ── 00 完整架构（一页看全）────────────────────────────────
def d00():
    g = Svg("ShareHub 系统总架构",
            "端 · 接入 · 10 业务服务 · 数据 · 横切支撑 · 南向与外部　｜　标「待建」= 设计已定未实现")
    ML, MW = 56, 1214
    EX, EW = 1294, 250

    g.band(148, 106, "① 端", "base")
    cw1 = (MW - 2 * 20) / 3
    for i, (t, sb) in enumerate([("运营端 ops-web", "Next.js · 98 菜单叶"),
                                 ("C端 c-app", "uni-app · 17 模块"),
                                 ("代理端（待建）", "复用运营端 + AGENT 范围")]):
        g.card(ML + i * (cw1 + 20), 182, cw1, 62, t, sb, "base")

    g.band(268, 106, "② 接入（受信头边缘注入 · 客户端同名头一律剥离）", "base")
    for i, (t, sb) in enumerate([("ops-bff", "/api 六前缀"),
                                 ("mp-bff", "/mp 端上可渲染形状"),
                                 ("agent-bff（待建）", "接口面为运营端子集")]):
        g.card(ML + i * (cw1 + 20), 302, cw1, 62, t, sb, "base")

    g.band(388, 240, "③ 业务服务（10 个 · 卡内为独占表前缀）", "money")
    svc = [("identity", "iam_", "base"), ("location", "loc_ agt_", "place"),
           ("device", "dev_ inv_", "dev"), ("workorder", "wo_ dev_alarm_", "dev"),
           ("trade", "ord_ price_", "money"),
           ("payment", "pay_", "money"), ("finance", "acct_ share_ stl_", "money"),
           ("customer", "usr_ mbr_", "user"), ("marketing", "mkt_ ad_ cs_", "user"),
           ("platform-config", "sys_ md_ dict_", "base")]
    cw3 = (MW - 4 * 16) / 5
    for i, (n, tb, hue) in enumerate(svc):
        x = ML + (i % 5) * (cw3 + 16)
        y = 424 + (i // 5) * 100
        g.card(x, y, cw3, 86, n, tb, hue, strong=(n == "trade"))

    g.band(650, 96, "④ 数据", "gray")
    cw4 = (MW - 3 * 16) / 4
    for i, (t, sb) in enumerate([("pb_core", "131 表 · 业务"), ("pb_pii", "独立 KMS · PDPL"),
                                 ("pb_auth", "独立 KMS · 凭据"), ("Redis", "会话·影子·幂等·锁")]):
        g.card(ML + i * (cw4 + 16), 682, cw4, 56, t, sb, "gray")

    g.band(754, 84, "⑤ 横切支撑（全域依赖，不画连线）", "place")
    chips = ["认证 双安全链", "RBAC 89 权限码", "数据范围 行级", "审计 WORM",
             "i18n zh/en/ar", "幂等 4 落法", "状态机", "事件 Publisher"]
    cx = ML + 6
    for c in chips:
        w = tw(c, 17) + 28
        g.o.append('<rect x="%s" y="794" width="%s" height="34" rx="17" fill="#FFFFFF" '
                   'stroke="%s" stroke-width="1.6"/>' % (cx, w, S["hue"]["place"][0]))
        g.text(cx + w / 2, 816, c, 17, S["ink2"], anchor="middle")
        cx += w + 10

    g.card(EX, 424, EW, 86, "access-gateway", "待建 · Netty/MQTT/回调", "dev", strong=True)
    for i, (t, sb) in enumerate([("供应商柜机", "TCP · MQTT · HTTP"),
                                 ("nearpay（待建）", "收单·预授权·退款"),
                                 ("推送 / 短信", "APNs · FCM · SMS")]):
        g.card(EX, 540 + i * 74, EW, 60, t, sb, "gray")

    for i in range(3):
        x = ML + i * (cw1 + 20) + cw1 / 2
        g.arrow(x, 246, x, 300, accent=True)
        g.arrow(x, 366, x, 386, accent=True)
    g.arrow(ML + MW / 2, 630, ML + MW / 2, 648, accent=True)
    g.arrow(ML + MW + 4, 467, EX - 6, 467, accent=True)
    g.text(EX - 8, 452, "指令 / 事件", 17, S["accent"], anchor="end")
    g.arrow(EX + EW / 2, 512, EX + EW / 2, 538)
    g.note(56, 872, "服务间依赖方向见「02 服务依赖」· 模块归属见「03 模块到服务」· 主链路见「04 借还主链路」")
    g.save("%s/00-overview.svg" % OUT)

# ── 01 分层总览 ────────────────────────────────────────────
def d01():
    g = Svg("ShareHub 系统分层", "端 → BFF → 业务服务 → 数据；南向与外部旁挂")
    lanes = [
        (152, "① 端",          "base",  [("运营端 ops-web","98 菜单叶"),("C端 c-app","17 模块"),("代理端","待建")]),
        (296, "② BFF",         "base",  [("ops-bff","/api/**"),("mp-bff","/mp/**"),("agent-bff","待建")]),
        (440, "③ 业务服务",     "money", [("10 个微服务","见「服务依赖」图")]),
        (584, "④ 数据",         "gray",  [("pb_core","131 表"),("pb_pii","独立 KMS"),("pb_auth","独立 KMS"),("Redis","会话·影子·幂等")]),
    ]
    for y, label, hue, cards in lanes:
        g.band(y, 116, label, hue)
        n = len(cards)
        cw = 250 if n > 1 else 420
        gap = 30
        total = n * cw + (n - 1) * gap
        x0 = (W - total) / 2 + 60
        for i, (t, sb) in enumerate(cards):
            g.card(x0 + i * (cw + gap), y + 44, cw, 60, t, sb, hue)
    # 层间箭头
    for y in (268, 412, 556):
        g.arrow(W / 2, y, W / 2, y + 28, accent=True)
    # 旁挂
    g.card(1180, 726, 356, 62, "access-gateway（待建）", "Netty · MQTT · /gw 回调", "dev")
    g.card(64, 726, 356, 62, "外部系统", "nearpay · 供应商柜机 · 推送", "gray")
    g.card(622, 726, 356, 62, "基础支撑", "认证 · RBAC · 数据范围 · 审计 · i18n", "base")
    g.arrow(800, 700, 800, 724)
    g.note(56, 852, "标记：无标记 = 已运行 · 「待建」= 设计已定未实现（ADR-016）")
    g.save("%s/01-layers.svg" % OUT)

# ── 02 服务依赖 ────────────────────────────────────────────
def d02():
    g = Svg("十个业务服务与依赖方向", "实线 = 同步调用 · 虚线 = 异步事件 · 自上而下为依赖方向")
    cw, ch = 236, 70
    # 分层摆放，箭头只朝下或同层水平，避免交叉
    P = {
        "marketing": (196, 178, "user"),  "trade":    (682, 178, "money"),
        "device":    (196, 330, "dev"),   "payment":  (682, 330, "money"), "customer": (1168, 330, "user"),
        "workorder": (196, 482, "dev"),   "finance":  (682, 482, "money"),
        "location":  (438, 620, "place"),
    }
    for n, (x, y, hue) in P.items():
        g.card(x, y, cw, ch, n, "", hue, strong=(n == "trade"))
    T = lambda n: (P[n][0] + cw / 2, P[n][1])
    B = lambda n: (P[n][0] + cw / 2, P[n][1] + ch)
    L = lambda n: (P[n][0], P[n][1] + ch / 2)
    R = lambda n: (P[n][0] + cw, P[n][1] + ch / 2)
    g.arrow(*R("marketing"), *L("trade"), "报障转单")
    g.arrow(682 + 60, B("trade")[1], *T("device"), "校验库存")
    g.arrow(*B("trade"), *T("payment"), "资金动作")
    g.arrow(682 + cw - 60, B("trade")[1], *T("customer"), "风控拦截")
    g.arrow(*B("device"), *T("workorder"), "AlarmRaised", dash=True)
    g.arrow(*B("payment"), *T("finance"), "PayCaptured", dash=True)
    g.arrow(*B("workorder"), 438 + 40, P["location"][1], "归属")
    g.arrow(*B("finance"), 438 + cw - 40, P["location"][1], "收益归属")
    # 共享服务：单独一条带 + 分隔线，明确「不在依赖图里」
    g.band(722, 122, "共享服务　被任何服务调用，自身不调任何人 —— 故不画连线", "base")
    g.card(556, 766, 236, 58, "identity", "", "base")
    g.card(826, 766, 262, 58, "platform-config", "", "base")
    g.note(56, 878, "identity = 鉴权 + 数据范围规格 · platform-config = 字典/业务规则/触达；画连线会是 20 条边覆盖全图")
    g.save("%s/02-service-deps.svg" % OUT)

# ── 03 模块 → 服务 ────────────────────────────────────────
def d03():
    g = Svg("15 个运营模块 → 10 个服务", "多对一归属；菜单按运营习惯组织，服务按数据归属切")
    rows = [
        ("设备管理 · 库存调拨", "device", "dev_ inv_", "dev"),
        ("告警管理 · 工单管理", "workorder", "wo_ dev_alarm_", "dev"),
        ("站点与点位 · 代理商", "location", "loc_ agt_", "place"),
        ("订单管理 · 计费定价", "trade", "ord_ price_", "money"),
        ("财务管理", "finance", "acct_ share_ stl_", "money"),
        ("用户管理", "customer", "usr_ mbr_", "user"),
        ("营销管理 · 客服管理", "marketing", "mkt_ ad_ cs_", "user"),
        ("员工与权限", "identity", "iam_", "base"),
        ("系统设置", "platform-config", "sys_ md_ dict_", "base"),
        ("经营看板 · 数据报表", "读模型（跨服务聚合）", "rpt_ 待建", "gray"),
    ]
    y0, rh = 160, 68
    for i, (mod, svc, tbl, hue) in enumerate(rows):
        y = y0 + i * rh
        g.card(70, y, 400, 52, mod, "", hue)
        g.arrow(478, y + 26, 596, y + 26, accent=(hue != "gray"))
        g.card(604, y, 380, 52, svc, "", hue, strong=True)
        g.text(1012, y + 34, tbl, S["fs_cardsub"], S["ink3"])
    g.note(56, 872, "「菜单归属 ≠ 服务归属」三处：告警归 workorder · 代理商归 location · 支付渠道归 payment")
    g.save("%s/03-module-to-service.svg" % OUT)

# ── 04 借还主链路 ──────────────────────────────────────────
def d04():
    g = Svg("借还主链路", "跨 4 个服务 + 南向网关 + 外部支付；虚线为异步事件")
    actors = [("c-app","user"),("trade","money"),("device","dev"),("payment","money"),("gateway","dev"),("柜机","gray")]
    n = len(actors); cw = 210; gap = 30
    x0 = (W - (n * cw + (n - 1) * gap)) / 2
    xs = {}
    for i, (a, hue) in enumerate(actors):
        x = x0 + i * (cw + gap)
        xs[a] = x + cw / 2
        g.card(x, 156, cw, 54, a, "", hue)
        g.line(x + cw / 2, 214, x + cw / 2, 818, S["rule"], 2, dash="4 6")
    steps = [
        (262, "c-app", "trade", "① 扫码借出", False),
        (312, "trade", "device", "② 校验可借", False),
        (362, "trade", "payment", "③ 免押预授权", False),
        (412, "trade", "gateway", "④ 下发弹仓指令", False),
        (462, "gateway", "柜机", "⑤ driver 编码下发", False),
        (512, "柜机", "gateway", "⑥ 弹出确认", True),
        (562, "gateway", "trade", "⑦ DeviceEvent", True),
        (612, "trade", "c-app", "⑧ 借出成功 → IN_USE", False),
        (694, "柜机", "gateway", "⑨ 归还上报", True),
        (744, "gateway", "trade", "⑩ 停计费 → 算费", True),
        (794, "trade", "payment", "⑪ 请款 capture", False),
    ]
    for y, a, b, label, dash in steps:
        x1, x2 = xs[a], xs[b]
        d = 1 if x2 > x1 else -1
        g.arrow(x1 + 6 * d, y, x2 - 10 * d, y, label, dash=dash, above=True)
    g.line(56, 656, W - 56, 656, S["rule"], 2, dash="8 8")
    g.text(64, 650, "归还", S["fs_lane"], S["ink3"], weight=700, spacing="1.2")
    g.note(56, 856, "超时未确认 → 重试 → 终态失败：订单转 EXCEPTION + 撤销预授权 + 自动开工单（saga 补偿）")
    g.save("%s/04-rent-flow.svg" % OUT)

# ── 05 权限四层 ────────────────────────────────────────────
def d05():
    g = Svg("请求的四层权限", "任一层缺失即为越权面；③ 是本轮补齐的重点")
    items = [
        ("① 认证", "你是谁", "Bearer → LoginUser|realm: STAFF·AGENT·CONSUMER", "base"),
        ("② 功能权限", "能不能调这个接口", "@perm.can(权限码)|89 个码 · 无码返 403", "base"),
        ("③ 数据范围", "能看哪几行", "SQL 自动追加|agent_no IN (...)", "place"),
        ("④ 出参脱敏", "能看到哪些明文", "手机/邮箱/密钥掩码|明文只在 pb_pii", "user"),
    ]
    cw, gap = 286, 32
    x0 = (W - (4 * cw + 3 * gap)) / 2
    ytop, yh = 250, 290
    for i, (t, q, detail, hue) in enumerate(items):
        x = x0 + i * (cw + gap)
        stroke, fill = S["hue"][hue]
        g.o.append('<rect x="%s" y="%s" width="%s" height="%s" rx="12" fill="%s" stroke="%s" stroke-width="2.4"/>'
                   % (x, ytop, cw, yh, fill, stroke))
        g.text(x + cw / 2, ytop + 54, t, 31, stroke, weight=750, anchor="middle")
        g.text(x + cw / 2, ytop + 92, q, S["fs_cardsub"], S["ink3"], anchor="middle")
        g.line(x + 38, ytop + 118, x + cw - 38, ytop + 118, stroke, 1.5)
        for k, ln in enumerate(detail.split("|")):
            g.text(x + cw / 2, ytop + 158 + k * 30, ln, 18, S["ink"], anchor="middle")
        if i < 3:
            g.arrow(x + cw + 5, ytop + yh / 2, x + cw + gap - 7, ytop + yh / 2, accent=True)
    ymid = ytop + yh / 2
    g.text(64, ymid + 8, "请求", 27, S["ink"], weight=700)
    g.arrow(134, ymid, x0 - 8, ymid, accent=True)
    xr = x0 + 3 * (cw + gap) + cw
    g.arrow(xr + 8, ymid, xr + 74, ymid, accent=True)
    g.text(xr + 86, ymid + 8, "响应", 27, S["ink"], weight=700)
    g.note(56, 690, "③ 数据范围的性质：未注册的表 = 全局放行。漏注册不报错、不告警，只会静默越权。")
    g.note(56, 724, "handler 是 fail-closed：一张表一旦注册，所有访问主体的维度都必须登记，漏一个那类主体全瞎。")
    g.note(56, 758, "已注册 5 表：loc_site · loc_location · dev_cabinet · ord_rent · wo_order")
    g.save("%s/05-authz-layers.svg" % OUT)

# ── 06 演进路径 ────────────────────────────────────────────
def d06():
    g = Svg("拆分演进路径", "按信号裂解，不预先拆（ADR-001 + ADR-016）")
    steps = [
        ("P0 现在", "模块化单体\n1 进程", "—", "gray"),
        ("P1", "抽 access-gateway", "接入真实硬件", "dev"),
        ("P2", "抽 payment + finance", "资金合规审计边界", "money"),
        ("P3", "抽 trade", "大促峰值需独立扩缩容", "money"),
        ("P4", "抽 device + workorder", "设备量级压过交易", "dev"),
        ("P5", "其余按需", "identity / platform-config 最后拆", "base"),
    ]
    cw, gap = 226, 28
    x0 = (W - (6 * cw + 5 * gap)) / 2
    for i, (ph, what, why, hue) in enumerate(steps):
        x = x0 + i * (cw + gap)
        stroke, fill = S["hue"][hue]
        g.o.append('<rect x="%s" y="250" width="%s" height="220" rx="11" fill="%s" stroke="%s" stroke-width="%s"/>'
                   % (x, cw, fill, stroke, 3 if i == 0 else 1.8))
        g.text(x + cw / 2, 296, ph, 28, stroke, weight=750, anchor="middle")
        for k, ln in enumerate(what.split("\n")):
            g.text(x + cw / 2, 344 + k * 28, ln, 21, S["ink"], weight=600, anchor="middle")
        g.line(x + 30, 396, x + cw - 30, 396, stroke, 1.4)
        words, line, lines = why.split(), "", []
        for wd in words:
            if len(line + wd) > 13:
                lines.append(line); line = wd
            else:
                line = (line + " " + wd).strip()
        lines.append(line)
        for k, ln in enumerate(lines[:2]):
            g.text(x + cw / 2, 424 + k * 24, ln, S["fs_cardsub"], S["ink3"], anchor="middle")
        if i < 5:
            g.arrow(x + cw + 4, 360, x + cw + gap - 6, 360, accent=True)
    g.note(56, 560, "每步的前置条件（缺一不可）：该服务的表已无跨域 join · 域间调用已走 /internal 或事件 ·")
    g.note(56, 592, "有链路追踪 · 有该服务独立的集成测试")
    g.note(56, 656, "「搬迁不重写」依赖三个前置设计：")
    g.note(80, 688, "① 表名 = 子域前缀 + 实体，库内唯一 → 拆库是纯 schema 迁移，零改名")
    g.note(80, 720, "② 跨域只有逻辑引用、不建物理 FK → 拆开后无跨库外键")
    g.note(80, 752, "③ 域间通信已走 DomainEventPublisher 抽象 → 进程内换 Kafka，业务代码零改动")
    g.note(56, 812, "反过来说：任何一次「图省事直接 join 另一个域的表」都是在给未来的拆分埋雷。")
    g.save("%s/06-evolution.svg" % OUT)

# ── 07 业务模块分层 ────────────────────────────────────────
def d07():
    """六层 32 模块。数据来自 backend/scripts/module-graph.py 的校验结果，
    不是手排的 —— 那份脚本保证了「132 张表全部归位、零循环依赖、仅 1 处反向依赖」。"""
    g = Svg("ShareHub 业务模块分层", "6 层 · 32 模块 · 132 张表全部归位　｜　规则：只能依赖同层或更低层")
    layers = [
        (150, "L5 服务",     "user",  ["客服"]),
        (256, "L4 资金",     "money", ["账务", "分润", "结算提现", "发票", "对账"]),
        (362, "L3 交易",     "money", ["计价", "订单", "支付"]),
        (468, "L2 运行管理",  "dev",   ["告警", "工单", "设备接入"]),
        (574, "L1 主体与资源", "place",  ["组织架构", "代理商", "消费者", "权益与营销", "场地拓展",
                                     "场地与点位", "设备资产", "固件", "库存", "资产归属"]),
        (680, "L0 平台底座",  "base",  ["租户", "认证授权", "审计", "主数据", "数据字典",
                                     "系统参数", "通知", "开放平台", "支付渠道", "厂商目录"]),
    ]
    for y, label, hue, mods in layers:
        g.band(y, 92, label, hue)
        n = len(mods)
        gap = 12
        avail = 1488 - 60
        cw = min(230, (avail - (n - 1) * gap) / n)
        x0 = 76
        for i, m in enumerate(mods):
            g.card(x0 + i * (cw + gap), y + 36, cw, 46, m, "", hue)
    for y in (242, 348, 454, 560, 666):
        g.arrow(1516, y + 22, 1516, y - 6, accent=True)
    g.note(56, 800, "箭头 = 依赖方向（上层依赖下层）。层内模块可互相依赖，如「库存」依赖「设备资产」。")
    g.note(56, 832, "唯一反向依赖：设备接入(L2) → 订单(L3)，由 gw_command_log.order_no —— 指令日志该记通用 biz_ref，不该硬绑订单。")
    g.note(56, 864, "边界靠三条约束顶住：每表必须归位 · 每条依赖可追到具体列 · 分层不允许反向依赖。")
    g.save("%s/07-module-layers.svg" % OUT)


# ── 08 双形态部署 ──────────────────────────────────────────
def d08():
    """ADR-017：一套代码，两种部署。左单体右微服务，中间是切换机制。"""
    g = Svg("一套代码，两种部署", "默认单体 · 需要时拆 5 个进程　｜　业务代码不知道自己在哪种形态")

    g.band(150, 250, "① 同一套业务代码（32 模块 / 132 张表全部有主）", "base")
    mods = [("platform", "底座+运营配置", "41 表"), ("core", "主链路", "56 表"),
            ("device-gateway", "南向接入", "9 表"), ("ops", "告警·工单·客服", "14 表"),
            ("finance", "账务·分润·结算", "12 表")]
    for i, (n, d, t) in enumerate(mods):
        g.card(80 + i * 292, 196, 274, 80, n, "%s · %s" % (d, t), "base")
    g.note(96, 316, "跨服务调用只经 *-api 接口；本地实现在 classpath 上就用本地，否则自动装远程 —— 业务代码一字不改。")
    g.note(96, 348, "四条纪律从今天起对单体生效：不跨模块 JOIN · 不跨服务事务 · 只经接口调用 · 写操作幂等。")

    g.band(430, 210, "② 形态 A：单体（默认）", "place")
    g.card(96, 480, 700, 70, "app-mono", "一个进程 · 一个库 · 跨模块 = 方法调用", "place")
    g.card(96, 562, 700, 56, "pb_core", "132 张表同库；报表可直接 JOIN", "gray")

    g.band(430, 210, "", "money") if False else None
    g.card(852, 480, 330, 70, "app-core", "主链路，单独扩容", "money")
    g.card(1198, 480, 330, 70, "app-platform", "读多写少，多副本", "money")
    g.card(852, 562, 216, 56, "app-gateway", "南向，变得最快", "money")
    g.card(1082, 562, 216, 56, "app-ops", "可延迟", "money")
    g.card(1312, 562, 216, 56, "app-finance", "批处理", "money")
    g.text(852, 462, "② 形态 B：拆 5 个进程（信号出现再切）", 21, "#A35D63", weight=700)

    g.note(96, 664, "16 条真实跨服务依赖，没有一条在同步主链路上：资金域读订单走事件 · 运行域读资源走只读 API + 快照 · 场地是慢变数据走缓存。")
    g.note(96, 696, "另有 18 条目录型引用（region_id / vendor_code / channel_code）不计成本：本地缓存 + 变更事件即可。")
    g.note(96, 740, "为什么 core 大：借出一笔要在一个事务里同时动订单/充电宝占用/支付预授权/券核销，拆开就得上分布式事务 —— 拿最热链路换整洁不值。")
    g.note(96, 784, "⚠️ 拆分前必须先解决：划拨级联跨界事务（AgentOwnershipSync 同事务写 platform+core 的表）· gw_command_log.order_no 反向依赖 · 5 个跨域重名业务键。")
    g.note(96, 828, "⚠️「跨界事务 0 处」这个结论不可信 —— 自动扫描不跟调用链，上面那处就是人工找到的。拆分前需做一次跟调用链的完整核查。")
    g.note(96, 864, "何时真拆：core 扩容需求分化 · finance 批处理影响主链路 · 合规要求资金独立部署 · 团队大到发布互相阻塞。信号出现前不拆。")
    g.save("%s/08-deploy-modes.svg" % OUT)


# ── 09 多设备类型与多供应商 ────────────────────────────────
def d09():
    """ADR-018：哪些共用、哪些按类型分化、供应商在哪一层。"""
    g = Svg("多设备类型与多供应商", "首批三类：充电宝 · 充电桩 · 储物柜　｜　按「变化的原因」分层，不按业务名词")

    g.band(150, 128, "① 设备无关（新增设备类型完全不动）", "place")
    for i, (t, sb) in enumerate([("站点·点位", "在哪"), ("柜机=容器", "dev_cabinet"),
                                 ("槽位=货位", "dev_slot"), ("告警·工单·库存", "怎么运转"),
                                 ("代理·分润·结算·发票", "钱怎么走")]):
        g.card(76 + i * 296, 196, 278, 62, t, sb, "place")

    g.band(302, 168, "② 订单：共性主表 + 类型扩展表（判据：资金侧只认主表）", "money")
    g.card(76, 348, 560, 62, "ord_order", "order_no · device_type · 用户 · 站点 · 状态 · 金额 · 时间", "money", strong=True)
    g.card(676, 348, 420, 62, "ord_rent_ext（充电宝）", "powerbank_no · 归还柜 · 押金 · 买断 · 时长", "money")
    g.card(1116, 348, 420, 62, "ord_charge_ext（充电桩）", "枪号 · kWh · 起止 SOC · 峰值功率", "money")
    g.note(76, 438, "状态机共性骨架 CREATED→ACTIVE→ENDED→SETTLED→CLOSED；ACTIVE 对充电宝=已借出、对充电桩=充电中。")

    g.band(486, 168, "③ 供应商：驱动插件，按设备类型分族（不是服务边界）", "dev")
    g.card(76, 532, 352, 62, "通用指令/事件", "REBOOT · ONLINE/OFFLINE · FAULT · OTA", "dev")
    g.card(444, 532, 352, 62, "充电宝族", "EJECT · SLOT_REPORT（各家私有协议）", "dev")
    g.card(812, 532, 352, 62, "充电桩族", "START/STOP · METER_VALUE（对齐 OCPP）", "dev")
    g.card(1180, 532, 356, 62, "储物柜族（最小样本）", "OPEN_CELL · CELL_OPENED · OVERTIME", "dev")
    g.note(76, 622, "驱动声明 (deviceType, protocol)；平台按 柜机的 vendor_code + device_type 路由。gw_vendor 加 device_types 多值列。")

    g.band(670, 92, "④ 两个接入面：南向已独立，北向先随 platform（伙伴>3 再拆）", "base")
    g.card(76, 712, 950, 56, "南向 device-gateway（已定，第 5 个服务）", "我们控制设备 · OCPP/私有协议 · 每接一家改一次", "base")
    g.card(1056, 712, 480, 56, "北向 互联互通（第 6 个的首选增长点）", "伙伴调我们下单 · OCPI · 伙伴是结算对手方", "base")

    g.note(56, 800, "储物柜检验并修正了抽象一处：押金出现在 3 类中的 2 类 → 升格规则「字段现身 ≥2 类即升主表」，deposit 升入 ord_order。")
    g.note(56, 836, "红线：供应商只做设备管理，订单/计价/支付/结算全在本平台 —— 厂商云的订单类 SaaS 一律不用；设备事件只是物理事实（EJECT_OK / ITEM_INSERTED），归还判定归 core。")
    g.note(56, 868, "新增一种设备 = 一张扩展表 + 一个 device.<type> 模块 + 一族指令事件 + 一个驱动。资金/场地/代理/工单四域完全不动。")
    g.save("%s/09-device-abstraction.svg" % OUT)


if __name__ == "__main__":
    import glob
    print("生成 PPT 用架构图（1600×900 · 16:9）：")
    for f in (d00, d01, d02, d03, d04, d05, d06, d07, d08, d09):
        f()
    print("\n越界自检：")
    ok = True
    for p in sorted(glob.glob("*.svg")):
        bad = check(p)
        print("  %-26s %s" % (p, "✓" if not bad else "⚠️ 越界 " + ", ".join(bad[:4])))
        ok = ok and not bad
    print("\n全部在画布内" if ok else "\n有越界，需修")
