# -*- coding: utf-8 -*-
"""
PPT 用架构图生成器。

为什么用生成器而不是手画 SVG：架构会变，图要跟着改。生成器让「改内容」和「改样式」分开 ——
调整配色/字号只改本文件的 STYLE，6 张图一起变；内容改动只动各 diagram_* 函数的 spec。

为什么不用 mermaid 出 PPT 图：字号/间距/画布比例不可控，导出的是位图或带外链的 SVG，
放进 PPT 会模糊或走形。这里直出 1600×900（16:9）矢量 SVG，PowerPoint 可直接插入，
右键「转换为形状」后还能继续编辑。
"""
import io, os

W, H = 1600, 900

# ── 风格：浅底 + 分层色带 + 白卡。刻意不用阴影/渐变（投影和打印都糊），层级靠字号与色带体现。
S = dict(
    bg="#FFFFFF",
    ink="#12181B", ink2="#4A585B", ink3="#7C8B8E",
    rule="#D8E0DF",
    accent="#0F7173",
    # 各层色相（与文档 mermaid classDef 保持一致，便于两处对照）
    hue={
        "base":  ("#5B7BA6", "#EEF2F8"),   # identity / platform-config
        "place": ("#5C8A63", "#EDF4EE"),   # location
        "dev":   ("#B07D3A", "#FBF3E8"),   # device / workorder
        "money": ("#A35D63", "#F9EEEF"),   # trade / payment / finance
        "user":  ("#7D63A3", "#F2EDF7"),   # customer / marketing
        "gray":  ("#6B7A7C", "#F0F3F3"),
    },
    # 字号：按 1600 宽画布定，投影到大屏仍清晰。这是本次「文字太小」的核心修正。
    fs_title=42, fs_sub=22, fs_lane=21, fs_card=27, fs_cardsub=19, fs_edge=18, fs_note=19,
    font="'PingFang SC','Hiragino Sans GB','Microsoft YaHei','Source Han Sans SC',"
         "'Noto Sans CJK SC',-apple-system,'Segoe UI',Roboto,sans-serif",
)

def tw(t, size):
    """文字宽度估算。CJK 约 1.0em、拉丁约 0.55em —— 按拉丁一刀切会让中文 chip/标签框装不住。"""
    w = 0.0
    for ch in t:
        w += size * (1.0 if ord(ch) > 0x2E80 else 0.55)
    return w


def esc(t):
    return (t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))

class Svg:
    def __init__(self, title, subtitle=""):
        self.o = []
        self.o.append(
            '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
            'font-family="%s">' % (W, H, W, H, S["font"]))
        self.o.append('<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" '
                      'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
                      '<path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker>'
                      '<marker id="ao" viewBox="0 0 10 10" refX="9" refY="5" '
                      'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
                      '<path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker></defs>'
                      % (S["ink2"], S["accent"]))
        self.o.append('<rect width="%d" height="%d" fill="%s"/>' % (W, H, S["bg"]))
        self.text(56, 68, title, S["fs_title"], S["ink"], weight=750, spacing="-1")
        if subtitle:
            self.text(56, 104, subtitle, S["fs_sub"], S["ink3"])
        self.line(56, 126, W - 56, 126, S["rule"], 2)

    def text(self, x, y, t, size, fill, weight=400, anchor="start", spacing="0", opacity=1):
        self.o.append('<text x="%s" y="%s" font-size="%s" fill="%s" font-weight="%s" '
                      'text-anchor="%s" letter-spacing="%s" opacity="%s">%s</text>'
                      % (x, y, size, fill, weight, anchor, spacing, opacity, esc(t)))

    def line(self, x1, y1, x2, y2, stroke, w=2, dash=None):
        d = ' stroke-dasharray="%s"' % dash if dash else ''
        self.o.append('<line x1="%s" y1="%s" x2="%s" y2="%s" stroke="%s" stroke-width="%s"%s/>'
                      % (x1, y1, x2, y2, stroke, w, d))

    def band(self, y, h, label, hue):
        stroke, fill = S["hue"][hue]
        self.o.append('<rect x="56" y="%s" width="%s" height="%s" rx="10" fill="%s"/>'
                      % (y, W - 112, h, fill))
        self.o.append('<rect x="56" y="%s" width="6" height="%s" rx="3" fill="%s"/>' % (y, h, stroke))
        self.text(78, y + 30, label, S["fs_lane"], stroke, weight=700, spacing="1.2")

    def card(self, x, y, w, h, title, sub="", hue="gray", strong=False):
        stroke, fill = S["hue"][hue]
        self.o.append('<rect x="%s" y="%s" width="%s" height="%s" rx="9" fill="#FFFFFF" '
                      'stroke="%s" stroke-width="%s"/>' % (x, y, w, h, stroke, 3 if strong else 1.8))
        cy = y + (h / 2 + 9) if not sub else y + h / 2 - 4
        self.text(x + w / 2, cy, title, S["fs_card"], S["ink"], weight=700 if strong else 600, anchor="middle")
        if sub:
            self.text(x + w / 2, y + h / 2 + 25, sub, S["fs_cardsub"], S["ink3"], anchor="middle")

    def arrow(self, x1, y1, x2, y2, label="", dash=None, accent=False, above=False):
        col = S["accent"] if accent else S["ink2"]
        d = ' stroke-dasharray="6 5"' if dash else ''
        self.o.append('<line x1="%s" y1="%s" x2="%s" y2="%s" stroke="%s" stroke-width="2.6" '
                      'marker-end="url(#%s)"%s/>' % (x1, y1, x2, y2, col, "ao" if accent else "a", d))
        if label:
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            if above:
                # 时序图惯例：标签在箭头上方。相邻泳道间距小时，居中压线的写法会让标签溢出泳道。
                self.text(mx, my - 12, label, S["fs_edge"], col, anchor="middle")
            else:
                lw = tw(label, S["fs_edge"])
                self.o.append('<rect x="%s" y="%s" width="%s" height="26" rx="4" fill="%s"/>'
                              % (mx - lw / 2 - 6, my - 19, lw + 12, S["bg"]))
                self.text(mx, my, label, S["fs_edge"], col, anchor="middle")

    def note(self, x, y, t, w=700):
        self.text(x, y, t, S["fs_note"], S["ink2"])

    def save(self, path):
        self.o.append('</svg>')
        io.open(path, 'w', encoding='utf-8').write("\n".join(self.o))
        print("  ✓ %s" % os.path.basename(path))


def check(path):
    """越界自检：元素跑出 1600×900 画布是最常见的低级错误（本次 05 就踩了两处），
    每次生成后自动扫一遍，比肉眼看截图可靠。"""
    import re, io as _io
    s = _io.open(path, encoding='utf-8').read()
    bad = []
    for m in re.finditer(r'<rect x="([-\d.]+)" y="([-\d.]+)" width="([\d.]+)" height="([\d.]+)"', s):
        x, y, w, h = map(float, m.groups())
        if x < 0 or y < 0 or x + w > W or y + h > H:
            bad.append("rect(%g,%g,%gx%g)" % (x, y, w, h))
    for m in re.finditer(r'<(?:line|text) x1?="([-\d.]+)"[^>]*?(?:x2="([-\d.]+)")?', s):
        for v in m.groups():
            if v is not None and (float(v) < 0 or float(v) > W):
                bad.append("x=%s" % v)
    return bad
