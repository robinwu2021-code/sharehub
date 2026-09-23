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


def box(g, x, y, w, h, hue, strong=True):
    """只画框不写字 —— 容器与多行文字卡用（card() 只能居中一行标题 + 一行说明）。"""
    stroke, _ = S["hue"][hue]
    g.o.append('<rect x="%s" y="%s" width="%s" height="%s" rx="10" fill="#FFFFFF" '
               'stroke="%s" stroke-width="%s"/>' % (x, y, w, h, stroke, 3 if strong else 1.8))


def chips(g, x, y, items, hue):
    stroke, _ = S["hue"][hue]
    for c in items:
        w = tw(c, 17) + 28
        g.o.append('<rect x="%s" y="%s" width="%s" height="34" rx="17" fill="#FFFFFF" '
                   'stroke="%s" stroke-width="1.6"/>' % (x, y, w, stroke))
        g.text(x + w / 2, y + 22, c, 17, S["ink2"], anchor="middle")
        x += w + 10


# ── 10 目标架构 v4（ADR-020 / ADR-021）───────────────────────
def d10():
    """技术架构方案 v4：业务单体 + 支付 / 协议对接两个独立服务 + 调度器；支付与任务共享 ai-shop。"""
    g = Svg("ShareHub 目标架构（v4）",
            "单体优先 · 支付与任务共享 ai-shop · 协议对接独立 · 认证 = neargo + ai-shop 方案　｜　ADR-020 / 021")
    AX, AW = 76, 904          # 业务单体
    SX, SW = 1030, 260        # 独立进程列
    EX, EW = 1330, 194        # 外部列

    g.band(148, 106, "① 端", "user")
    cw = (AW - 32) / 3
    for i, (t, sb) in enumerate([("运营端 ops-web", "Next.js 静态 · /api"),
                                 ("代理端", "ops-web 受限视图"),
                                 ("C端 c-app", "App · 微信小程序 · H5")]):
        g.card(AX + i * (cw + 16), 182, cw, 62, t, sb, "user")
    g.text(SX, 206, "外部回调同样经 nginx 进入：", 19, S["ink3"])
    g.text(SX, 234, "支付 /pay/callback · 厂商 /gw · 充电桩 /ocpp", 19, S["ink3"])

    g.band(270, 84, "② 接入 nginx（TLS · 静态托管 · 按前缀分流）", "base")
    chips(g, 76, 306, ["/ → ops-web", "/c/ → c-app H5", "/api /mp → app", "/pay/callback → pay-svc",
                       "/gw /ocpp → gateway", "/internal 外网封死"], "base")
    for i in range(3):
        x = AX + i * (cw + 16) + cw / 2
        g.arrow(x, 246, x, 268, accent=True)
        g.arrow(x, 356, x, 404, accent=True)

    g.band(370, 282, "③ 应用（4 个进程）", "money")
    box(g, AX, 406, AW, 232, "money")
    g.text(AX + 20, 438, "sharehub-app · 业务单体（无状态 · 可 N 副本）", 24, S["ink"], weight=700)
    mw = (AW - 40 - 36) / 4
    for i, (t, sb) in enumerate([("platform", "底座·配置·场地·代理"), ("core", "订单·计价·设备·用户"),
                                 ("ops", "告警·工单·客服"), ("finance", "账务·分润·结算·发票")]):
        g.card(AX + 20 + i * (mw + 12), 452, mw, 70, t, sb, "money", strong=(t == "core"))
    rw = (AW - 40 - 24) / 3
    for i, (t, sb) in enumerate([("通用认证（嵌入）", "neargo + ai-shop 会话方案"),
                                 ("设备类型模块", "宝 · 桩 · 椅 · 柜"),
                                 ("事件 · 任务目标", "Outbox · /internal/job")]):
        g.card(AX + 20 + i * (rw + 12), 534, rw, 62, t, sb, "base")
    g.text(AX + 20, 624, "模块间 = 方法调用 + 本地事务；边界按跨进程标准守（不跨模块 JOIN / 事务，只经 api 接口）",
           18, S["ink2"])

    for k, (y, hue, title, sub) in enumerate([(406, "money", "pay-svc", "共享 ai-shop pay · 独立部署"),
                                               (488, "dev", "sharehub-gateway", "协议对接 · 驱动插件"),
                                               (570, "base", "sharehub-job", "共享 ai-shop 任务 · 调度器")]):
        box(g, SX, y, SW, 68, hue)
        g.text(SX + SW / 2, y + 30, title, 24, S["ink"], weight=700, anchor="middle")
        g.text(SX + SW / 2, y + 56, sub, 17, S["ink2"], anchor="middle")

    g.card(EX, 406, EW, 68, "支付通道", "AE 卡通道 · 微信", "gray")
    g.card(EX, 488, EW, 68, "厂商云 / 设备", "宝 · 桩 · 椅 · 柜", "gray")
    g.card(EX, 570, EW, 68, "短信 · 推送", "地图 · 邮件", "gray")

    for y0 in (430, 512):          # 实线 = 同步调用，虚线 = 事件 / 回调
        g.arrow(AX + AW, y0, SX - 2, y0)
        g.arrow(SX, y0 + 24, AX + AW + 2, y0 + 24, dash=True)
        g.arrow(SX + SW, y0, EX - 2, y0)
        g.arrow(EX, y0 + 24, SX + SW + 2, y0 + 24, dash=True)
    g.arrow(SX, 604, AX + AW + 2, 604, accent=True)           # 调度器 → 各进程的任务端点

    g.band(668, 100, "④ 数据（一库一写入方）", "gray")
    dw = (AW - 32) / 3
    for i, (t, sb) in enumerate([("sharehub_core", "业务 · 单体独占"), ("sharehub_auth", "凭据 · 会话"),
                                 ("sharehub_pii", "个人数据 · PDPL")]):
        g.card(AX + i * (dw + 16), 702, dw, 56, t, sb, "gray")
    g.card(SX, 702, SW, 56, "各进程自有库", "_pay · _gw · _job", "gray")
    g.card(EX, 702, EW, 56, "中间件", "Redis · EMQX 按需", "gray")
    g.arrow(AX + AW / 2, 640, AX + AW / 2, 700, accent=True)

    g.band(780, 86, "⑤ 横切（全域依赖，不画连线）", "place")
    chips(g, 76, 820, ["服务间凭证 X-Internal-Token", "Outbox 事件（HTTP 推送 → 阶段二 MQ）",
                       "链路追踪 traceId", "审计 WORM", "zh/en/ar · RTL", "PDPL · 不落卡号"], "place")
    g.note(56, 890, "实线 = 同步调用　虚线 = 事件 / 回调（至少一次 · 消费幂等）　青色 = 调度器 cron 回调任务端点"
                    "　｜　详见 docs/technical/v4/")
    g.save("%s/10-target-architecture.svg" % OUT)


# ── 11 协议对接服务 ─────────────────────────────────────────
# 11/12 共用：五条带，带高 100、带间 18；卡片距带顶 34（给标签留位），连线只走带与带之间的缝，不穿标签。
BANDS = (148, 266, 384, 502, 620)
BH, CT = 100, 34


def gap_arrows(g, xs, band_i, both=False):
    """在第 band_i 条带与下一条带之间画短箭头；both=True 时并排画一上一下（下行实线、上行虚线）。"""
    y1, y2 = BANDS[band_i] + BH - 4, BANDS[band_i + 1] - 2
    for x in xs:
        if both:
            g.arrow(x - 12, y1, x - 12, y2, accent=True)
            g.arrow(x + 12, y2, x + 12, y1, dash=True)
        else:
            g.arrow(x, y1, x, y2, accent=True)


def d11():
    """sharehub-gateway：逻辑上一个服务；传输 / 内核 / 驱动三层，对业务只暴露三样契约。"""
    g = Svg("协议对接服务 sharehub-gateway",
            "对接第三方充电宝 · 充电桩平台与设备　｜　逻辑上一个服务：可以是 1 个进程 / 插件化 / 多进程")
    cw = (1448 - 48) / 4
    col = [76 + i * (cw + 16) for i in range(4)]
    mid = [c + cw / 2 for c in col]
    kw = (1448 - 64) / 5
    tw3 = (1448 - 32) / 3

    g.band(BANDS[0], BH, "① 对端（三种接入形态）", "gray")
    y = BANDS[0] + CT
    g.card(col[0], y, cw, 60, "A 厂商云", "HTTPS API + Webhook 回调", "gray")
    g.card(col[1], y, cw * 2 + 16, 60, "B 设备直连", "TCP 私有协议 · MQTT（经 EMQX）", "gray")
    g.card(col[3], y, cw, 60, "C 行业标准", "OCPP 1.6J / 2.0.1（WebSocket）", "gray")
    gap_arrows(g, mid, 0, both=True)

    g.band(BANDS[1], BH, "② 传输适配 transport（按协议一个，互不依赖）", "dev")
    y = BANDS[1] + CT
    for i, (t, sb) in enumerate([("http-webhook", "/gw/callback/{vendor} · 验签"),
                                 ("tcp-netty", "长连接会话 · 帧编解码"),
                                 ("mqtt", "订阅上行 · 发布指令"),
                                 ("ocpp-ws", "/ocpp/{chargePointId}")]):
        g.card(col[i], y, cw, 60, t, sb, "dev")
    gap_arrows(g, mid, 1, both=True)

    g.band(BANDS[2], BH, "③ 接入内核 kernel（与协议、厂商都无关）", "base")
    y = BANDS[2] + CT
    for i, (t, sb) in enumerate([("驱动路由", "(厂商, 设备类型) → 驱动"),
                                 ("指令生命周期", "受理→下发→ACK→重试→终态"),
                                 ("事件归一化", "报文 → NormalizedEvent"),
                                 ("会话与 SN 映射", "SN→节点 · 在线态 · LWT"),
                                 ("报文留痕", "上下行原文 · 脱敏 · 可回放")]):
        g.card(76 + i * (kw + 16), y, kw, 60, t, sb, "base")
    gap_arrows(g, [76 + kw / 2], 2)

    g.band(BANDS[3], BH, "④ 驱动插件（每家厂商 = 1 个 driver + manifest；指令 / 事件按设备族白名单）", "dev")
    y = BANDS[3] + CT
    for i, (t, sb) in enumerate([("通用族", "REBOOT · ONLINE · FAULT · OTA"),
                                 ("充电宝族", "EJECT_SLOT · ITEM_INSERTED"),
                                 ("充电桩族（OCPP）", "START/STOP · METER_VALUE"),
                                 ("储物柜族", "OPEN_CELL · CELL_CLOSED")]):
        g.card(col[i], y, cw, 60, t, sb, "dev")

    g.band(BANDS[4], BH, "⑤ 对业务的契约（sharehub-app 只看得到这三样；内核直出，不经驱动）", "money")
    y = BANDS[4] + CT
    for i, (t, sb) in enumerate([("DeviceCommandPort", "app → gateway · 受理即回 commandId"),
                                 ("DeviceEvent", "gateway → app · Outbox 推送 · 至少一次"),
                                 ("TelemetryQueryPort", "心跳 / 在线态 · 只读查询")]):
        g.card(76 + i * (tw3 + 16), y, tw3, 60, t, sb, "money")

    g.note(56, 766, "演进（对业务始终是一个服务）：① 单进程，驱动 = Maven 子模块　→　② 插件化，驱动 jar 放 plugins/ 独立类加载，")
    g.note(56, 794, "　　不重编内核即可接新厂商　→　③ 按传输拆多进程（OCPP / TCP 长连接节点），共用内核库、pb_gw 与 /internal/gw 契约。")
    g.note(56, 832, "红线：只报物理事实（EJECT_OK / ITEM_INSERTED），归还判定归 core · 不碰订单与资金 · 指令不带消费者个人信息（PDPL）。")
    g.note(56, 866, "北向伙伴（OCPI，伙伴调我们下单）不走本服务 —— 它是资金对手方，归 interconnect 模块，与南向互不混用。")
    g.save("%s/11-gateway-service.svg" % OUT)


# ── 12 支付：共享 ai-shop pay 模块 ──────────────────────────
def d12():
    """支付不重做：ai-shop 的 pay 模块两项目共用一份代码，各自部署实例；ShareHub 只写适配与编排。"""
    g = Svg("支付：共享 ai-shop pay 模块",
            "一份代码（抽为中立的 neargo-pay）· 两个项目各自部署实例与库 · ShareHub 只写适配层与支付编排　｜　ADR-020")
    cw = (1448 - 48) / 4
    col = [76 + i * (cw + 16) for i in range(4)]
    tw3 = (1448 - 32) / 3

    g.band(BANDS[0], BH, "① 调用方（各自项目，只经契约，不直连支付表）", "base")
    y = BANDS[0] + CT
    g.card(col[0], y, cw, 60, "ai-shop · shop-app", "电商下单 · 售后 · 商户结算", "base")
    g.card(col[1], y, cw * 2 + 16, 60, "ShareHub · sharehub-app",
           "借出预授权 · 归还请款 · 钱包充值 · 退款 · 提现打款", "base", strong=True)
    g.card(col[3], y, cw, 60, "c-app / 小程序", "经 app 拿收银参数，端上 SDK 拉起", "base")
    gap_arrows(g, [col[0] + cw / 2, col[1] + cw + 8], 0)

    g.band(BANDS[1], BH, "② 接入契约（各项目自己的一层薄适配）", "base")
    y = BANDS[1] + CT
    for i, (t, sb) in enumerate([("ai-shop：*AppService", "embedded / remote 二选一（已有）"),
                                 ("ShareHub：PaymentPort", "RemotePaymentPort · 内部 HTTP · 服务凭证"),
                                 ("支付事件回推", "PAID · AUTHORIZED · CAPTURED · REFUNDED")]):
        g.card(76 + i * (tw3 + 16), y, tw3, 60, t, sb, "base", strong=(i == 1))
    gap_arrows(g, [76 + tw3 / 2, 76 + tw3 + 16 + tw3 / 2], 1)

    g.band(BANDS[2], BH, "③ 共享 pay 模块（一份代码 · 两项目契约测试都过才能改）", "money")
    y = BANDS[2] + CT
    for i, (t, sb) in enumerate([("pay-channel", "PayGateway SPI · 验签 · 通道配置"),
                                 ("pay-domain", "支付流水 · 退款 · 对账 · 提现"),
                                 ("pay-svc", "独立进程 · /internal/pay"),
                                 ("按需装配", "积分 · 商户分账：ShareHub 关")]):
        g.card(col[i], y, cw, 60, t, sb, "money", strong=(i < 3))
    gap_arrows(g, [col[0] + cw / 2], 2)

    g.band(BANDS[3], BH, "④ 通道适配器（PayGateway 实现 · 注入即路由）　✓ 已有　○ 待建", "money")
    y = BANDS[3] + CT
    for i, (t, sb) in enumerate([("✓ 微信 / 支付宝", "CN · JSAPI / APP / H5"),
                                 ("✓ Stub / Test", "开发 · 演示 · 自动化测试"),
                                 ("○ AE 卡通道", "N-Genius · Tap · PayTabs"),
                                 ("○ 预授权能力", "preAuth · capture · release")]):
        g.card(col[i], y, cw, 60, t, sb, "money", strong=(i >= 2))
    gap_arrows(g, [col[0] + cw / 2, col[2] + cw / 2], 3, both=True)

    g.band(BANDS[4], BH, "⑤ 部署（同一代码，两套实例 —— 主体、市场、数据驻留都不同）", "gray")
    y = BANDS[4] + CT
    for i, (t, sb) in enumerate([("ai-shop pay-svc", ":8083 · 库 ai_shop（独立数据源）"),
                                 ("ShareHub pay-svc", ":8092 · 库 pb_pay · MENA 驻留"),
                                 ("通道回调", "nginx /pay/callback/{channel} → 各自实例")]):
        g.card(76 + i * (tw3 + 16), y, tw3, 60, t, sb, "gray", strong=(i == 1))

    g.note(56, 772, "ShareHub 不用 pay-domain 的结算 / 积分：分润与结算规则仍在 ShareHub finance —— 场地 / 代理分成 ≠ 电商商户结算，按需装配关掉。")
    g.note(56, 806, "共享前要做的三件事：① 抽离到中立位置、依赖从 shop-base 换成 neargo commons　② 41 处反向引用（MerchantQueryPort 27 ·")
    g.note(56, 834, "　　SettleSourcePort 14）泛化为 bizType + bizNo 的 SPI，两项目各自实现　③ 回调入口随独立部署移进 pay-svc（今天在 shop-core）。")
    g.note(56, 870, "回调顺序沿用 ai-shop：验签 → 回查通道 → 落库 → 发事件；回查失败返回 FAIL 让通道重推。卡号不进本系统，通道密钥只在 pay 库（KMS）。")
    g.save("%s/12-pay-service.svg" % OUT)


# ── 13 通用设备模型（ADR-021）──────────────────────────────
def d13():
    """四类设备同一模型：按使用形态分化；新增设备只加类型模块与（可复用的）扩展。"""
    g = Svg("通用设备模型", "充电宝为主 · 充电桩 · 按摩椅 · 储物柜同一模型　｜　按「使用形态」分化，不按设备名　｜　ADR-021")

    g.band(148, 104, "① 物理层级（全部设备通用）", "place")
    chain = [("站点 Site", "归属：代理 · 区域"), ("点位 Location", "站内投放位"), ("设备 Device", "柜机 / 桩 / 椅 / 柜"),
             ("槽位 Slot", "仓位 / 枪 / 座位 / 格口"), ("物品 Item", "仅借还型：充电宝")]
    cw = (1448 - 4 * 40) / 5
    for i, (t, sb) in enumerate(chain):
        x = 76 + i * (cw + 40)
        g.card(x, 182, cw, 60, t, sb, "place", strong=(i in (2, 3)))
        if i < 4:
            g.arrow(x + cw + 4, 212, x + cw + 36, 212, accent=True)

    g.band(268, 290, "② 四类设备：差在「怎么用」，不在「设备」", "dev")
    LX, LW = 76, 180
    TX = LX + LW + 12
    tcw = (1448 - LW - 12 - 3 * 12) / 4
    types = [("充电宝（主业务）", "POWERBANK"), ("充电桩", "EV_CHARGER"), ("按摩椅（新样本）", "MASSAGE_CHAIR"), ("储物柜", "LOCKER")]
    for i, (t, code) in enumerate(types):
        g.card(TX + i * (tcw + 12), 304, tcw, 56, t, code, "dev", strong=(i == 2))
    rows = [("使用形态", ["RENTAL 借还", "SESSION 就地使用", "SESSION 就地使用", "SESSION 就地使用"]),
            ("槽位", ["仓位 BAY", "枪 CONNECTOR", "座位 SEAT", "格口 CELL"]),
            ("计量", ["MINUTE 按时长", "KWH 按电量", "PACKAGE 套餐", "MINUTE 按时长"]),
            ("付费模式", ["PREAUTH → DEPOSIT", "PREAUTH", "PREPAY 先付后用", "PREPAY / PREAUTH"]),
            ("指令族", ["POWERBANK", "EV_CHARGER（对齐 OCPP）", "SESSION（通用）", "LOCKER"]),
            ("订单扩展表", ["ord_rent_ext", "ord_charge_ext", "ord_session_ext（通用）", "ord_locker_ext"])]
    for r, (label, vals) in enumerate(rows):
        y = 392 + r * 28
        g.text(LX + 20, y, label, 19, S["ink2"], weight=700)
        for i, v in enumerate(vals):
            g.text(TX + i * (tcw + 12) + tcw / 2, y, v, 19,
                   S["ink"] if i == 2 else S["ink2"], weight=600 if i == 2 else 400, anchor="middle")

    g.band(574, 96, "③ 全部复用：新增一种设备，这些一行不改", "money")
    chips(g, 76, 614, ["设备台账 dev_device", "站点 · 点位", "订单主表 ord_order", "支付", "分润 · 账务",
                       "结算 · 发票", "工单 · 告警 · 客服", "运营端列表（按 deviceType 筛）"], "money")

    g.band(686, 104, "④ 新增一种设备 = 5 件事（按摩椅：只写 ② 和驱动，③ ④ 复用）", "base")
    steps = [("① 登记类型", "md_device_type 一行"), ("② 类型模块", "DeviceTypeModule"),
             ("③ 扩展表", "能复用就复用"), ("④ 指令族 + 驱动", "族复用，驱动按厂商"), ("⑤ 计价方案", "后台配套餐 / 电价")]
    sw = (1448 - 4 * 16) / 5
    for i, (t, sb) in enumerate(steps):
        g.card(76 + i * (sw + 16), 720, sw, 60, t, sb, "base", strong=(i == 1))

    g.note(56, 834, "命名：平台层只用 device / slot / order / start · end · stop；设备名只在 device.<type> 包、ord_<x>_ext、驱动族与类型专属清单里出现。")
    g.note(56, 868, "改名：dev_cabinet → dev_device · cabinet_no → device_no · EV_PILE → EV_CHARGER · IN_USE / RETURNED → ACTIVE / ENDED · /orders/rent → POST /orders")
    g.save("%s/13-device-model.svg" % OUT)


# ── 14 后端代码架构（v4/09）────────────────────────────────
def panel(g, x, y, w, h, label, hue):
    stroke, fill = S["hue"][hue]
    g.o.append('<rect x="%s" y="%s" width="%s" height="%s" rx="10" fill="%s"/>' % (x, y, w, h, fill))
    g.o.append('<rect x="%s" y="%s" width="6" height="%s" rx="3" fill="%s"/>' % (x, y, h, stroke))
    g.text(x + 22, y + 30, label, S["fs_lane"], stroke, weight=700, spacing="1.2")


def d14():
    """后端代码架构：模块层序（≈ ai-shop 同名层）+ 业务域包布局 + 复用来源 + 棘轮。"""
    g = Svg("后端代码架构（参照 ai-shop）", "模块层序 · 业务域包布局 · 能复用尽量复用　｜　v4/09")

    panel(g, 56, 148, 804, 520, "① 模块层序（上层依赖下层 · 右侧为 ai-shop 对应层）", "base")
    layers = [("sharehub-app", "配置 · 跨域编排 · 任务目标", "≈ shop-app", "base"),
              (None, None, "≈ shop-core 等业务域", "money"),
              ("sharehub-api", "Port · 事件 · 远程实现", "≈ shop-base/spi", "base"),
              ("sharehub-store", "BaseEntity · CRUD · 幂等 · Outbox", "≈ shop-store-mybatis", "base"),
              ("sharehub-auth", "Realm · Perms · 响应包裹 · 异常", "≈ shop-base-auth", "base"),
              ("sharehub-common", "BizException · 业务号 · AfterCommit", "≈ shop-base", "base"),
              ("neargo 共享件", "job · auth-store · internal · store", "ai-neargo（下沉）", "place")]
    for i, (t, sub, eq, hue) in enumerate(layers):
        y = 192 + i * 64
        if t is None:                                          # svc 行：四个业务模块
            for j, n in enumerate(["svc-platform", "svc-core", "svc-ops", "svc-finance"]):
                box(g, 76 + j * 142, y, 132, 50, "money", strong=(n == "svc-core"))
                g.text(76 + j * 142 + 66, y + 32, n, 19, S["ink"], weight=700, anchor="middle")
        else:
            box(g, 76, y, 560, 50, hue, strong=(i in (3, 5)))
            g.text(92, y + 33, t, 22, S["ink"], weight=700)
            g.text(306, y + 32, sub, 17, S["ink2"])
        g.text(650, y + 32, eq, 17, S["ink3"])

    panel(g, 880, 148, 664, 520, "② 业务域包布局（照 ai-shop）", "place")
    box(g, 900, 190, 624, 272, "place")
    g.text(920, 220, "ai.neargo.sharehub.<domain>", 20, S["ink"], weight=700)
    rows = [("api/ops · mp · internal", "控制器随域走；Req 嵌在控制器"),
            ("service/ · service/impl/", "接口 + 实现；Command；状态机"),
            ("entity/", "一实体一文件"),
            ("mapper/", "XxxMappers（每域一个）"),
            ("dto/", "XxxVO（of(entity) 唯一出口）"),
            ("port/", "实现 sharehub-api 的 Port"),
            ("event/ · job/", "OutboxConsumer · JobHandler")]
    for i, (a, b) in enumerate(rows):
        y = 252 + i * 30
        g.text(920, y, a, 18, S["ink"], weight=600)
        g.text(1160, y, b, 17, S["ink2"])
    rules = ["✗ 实体 / Map 作请求体或响应（现 78 + 33 处）",
             "✗ 控制器依赖 Mapper · 服务层碰 HttpServletRequest",
             "✗ @Scheduled（任务只经 JobHandler）",
             "✓ 跨域读走 Port · 跨域写 AfterCommit · 异步 Outbox + 去重",
             "✓ 失败只抛 BizException(ErrorCode)，分段码 + 三语"]
    for i, r in enumerate(rules):
        g.text(900, 494 + i * 34, r, 18, S["ink2"])

    g.band(684, 122, "③ 复用 ai-shop", "money")
    g.text(76, 750, "下沉共享", 18, S["hue"]["money"][0], weight=700)
    chips(g, 170, 728, ["neargo-job", "neargo-auth-store", "neargo-pay", "InternalClient",
                        "幂等 · 事件去重 · Outbox", "BizException · ErrorCode"], "money")
    g.text(76, 790, "照搬约定", 18, S["hue"]["money"][0], weight=700)
    chips(g, 170, 768, ["ArchitectureTest + known-*.txt", "H2 + 生成 schema + e2e", "Req / Command / VO",
                        "assertTransit 状态机", "AfterCommit", "${ENV:默认值}"], "money")

    g.note(56, 846, "有意不同：信封用 neargo {message, list} · 403 用真实 HTTP 状态 · 金额 BigDecimal（支付边界换算 minor）· 注入 Clock")
    g.note(56, 880, "棘轮：实体请求体 78→0 · Map 契约 33→0 · svc 单测 0→全覆盖 · 写开发库的测试→0 · 平台层 cabinet* 751 处→0")
    g.save("%s/14-backend-code.svg" % OUT)


if __name__ == "__main__":
    import glob
    print("生成 PPT 用架构图（1600×900 · 16:9）：")
    for f in (d00, d01, d02, d03, d04, d05, d06, d07, d08, d09, d10, d11, d12, d13, d14):
        f()
    print("\n越界自检：")
    ok = True
    for p in sorted(glob.glob("*.svg")):
        bad = check(p)
        print("  %-26s %s" % (p, "✓" if not bad else "⚠️ 越界 " + ", ".join(bad[:4])))
        ok = ok and not bad
    print("\n全部在画布内" if ok else "\n有越界，需修")
