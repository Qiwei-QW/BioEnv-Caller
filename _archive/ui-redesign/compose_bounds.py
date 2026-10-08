# -*- coding: utf-8 -*-
"""UI 可改动边界图 —— 只基于「虚拟机适配」这一份基线（2026-10-03 构建）。
绿 = 外观可以改；红 = 被回归测试或插件契约锁住，改了会红。
"""
import os
from PIL import Image, ImageDraw, ImageFont

D = r"C:\Users\14231\Desktop\TBtools插件\UI改造方案\_方案与对照"
MS = r"C:\Windows\Fonts\msyh.ttc"
MSB = r"C:\Windows\Fonts\msyhbd.ttc"

GREEN = (0x0B, 0x93, 0x66)
GREEN_F = (0x0B, 0x93, 0x66, 42)
RED = (0xC0, 0x2A, 0x2A)
RED_F = (0xC0, 0x2A, 0x2A, 42)
INK = (0x11, 0x11, 0x14)
SUB = (0x47, 0x47, 0x51)
MUTED = (0x6E, 0x6E, 0x78)
ACCENT = (0x4F, 0x46, 0xE5)

W, PAD = 1800, 40
IMGW = 1150
LEGX = PAD + IMGW + 46
LEGW = W - PAD - LEGX
HEAD = 132
GAP = 34
CAP = 48


def F(s, b=False):
    return ImageFont.truetype(MSB if b else MS, s)


_dd = ImageDraw.Draw(Image.new("RGB", (8, 8)))


def _tokens(t):
    """CJK 逐字成 token；拉丁词整词成 token；空格成 token。"""
    out, buf = [], ""
    for ch in t:
        if ch == "\n":
            if buf:
                out.append(buf); buf = ""
            out.append("\n")
        elif "\u4e00" <= ch <= "\u9fff" or ch in "，。、；：（）「」—→·":
            if buf:
                out.append(buf); buf = ""
            out.append(ch)
        elif ch == " ":
            if buf:
                out.append(buf); buf = ""
            out.append(" ")
        else:
            buf += ch
    if buf:
        out.append(buf)
    return out


def wrap(t, font, maxw):
    lines, cur = [], ""
    for tk in _tokens(t):
        if tk == "\n":
            lines.append(cur); cur = ""
            continue
        if _dd.textlength(cur + tk, font=font) > maxw and cur:
            lines.append(cur.rstrip())
            cur = "" if tk == " " else tk
        else:
            cur += tk
    lines.append(cur.rstrip())
    return lines


def dashed(dr, box, color, width=2, dash=9, gap=6):
    x0, y0, x1, y1 = box
    for x in range(x0, x1, dash + gap):
        dr.line([x, y0, min(x + dash, x1), y0], fill=color, width=width)
        dr.line([x, y1, min(x + dash, x1), y1], fill=color, width=width)
    for yy in range(y0, y1, dash + gap):
        dr.line([x0, yy, x0, min(yy + dash, y1)], fill=color, width=width)
        dr.line([x1, yy, x1, min(yy + dash, y1)], fill=color, width=width)


def badge(dr, cx, cy, t, color, r=15):
    dr.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color, outline=(255, 255, 255), width=2)
    f = F(16, True)
    dr.text((cx - dr.textlength(t, font=f) / 2, cy - 11), t, font=f, fill=(255, 255, 255))


ROWS = [
    dict(file="ui-main.png", native=1400, width=IMGW,
         title="① 主界面", sub="1400×900　真实渲染",
         zones=[
             ("1", GREEN, (0, 0, 1400, 45), (700, 22)),
             ("2", GREEN, (3, 45, 277, 863), (140, 330)),
             ("3", GREEN, (282, 58, 1044, 710), (663, 300)),
             ("4", GREEN, (282, 716, 1044, 833), (663, 775)),
             ("5", GREEN, (1050, 45, 1398, 863), (1224, 300)),
             ("6", GREEN, (282, 835, 1398, 899), (840, 867)),
             ("A", RED, (277, 45, 285, 899), (281, 470)),
             ("A", RED, (1044, 45, 1052, 871), (1048, 620)),
             ("A", RED, (277, 711, 1052, 719), (900, 700)),
             ("B", RED, (80, 8, 180, 37), (130, 22)),
         ],
         legend=[
             ("1", GREEN, "顶栏：配色、字体、间距，以及重排成「品牌区 + 分段控件 + 动作 + 语言开关」。只要分隔条数量不变就行"),
             ("2", GREEN, "左栏工具树：字体、行高、环境与工具的圆点颜色"),
             ("3", GREEN, "表单区：留白、分组、控件圆角与边框（控件类型不能换，见 C）"),
             ("4", GREEN, "命令预览：可整体改成深色终端（MainPanel.java:1339 一处 setBackground）"),
             ("5", GREEN, "输出区：字体、底色、标题样式"),
             ("6", GREEN, "底部按钮行 + 状态栏：按钮主次分级、状态栏配色"),
             ("A", RED, "三条分隔条：数量必须恰好 2 横 + 1 纵；粗细 ≥ 4px；树侧初始位置 120–400px；右侧 ≥ 300px；每条都要能拖动"),
             ("B", RED, "「连接方式」下拉框实例本身：字段名 modeBox、下标语义 0=WSL / 1=Linux VM 被测试反射锁定"),
         ]),
    dict(file="ui-form.png", native=1400, width=IMGW,
         title="② 参数表单", sub="1400×900　真实渲染",
         zones=[
             ("7", GREEN, (6, 120, 1394, 612), (700, 140)),
             ("8", GREEN, (10, 158, 1390, 352), (700, 258)),
             ("C", RED, (156, 194, 1386, 268), (272, 231)),
             ("D", RED, (1306, 88, 1378, 128), (1342, 108)),
         ],
         legend=[
             ("7", GREEN, "「选项 / 附加参数」两块的分区边框与标题样式（标题文字必须仍从同一个 i18n key 取）"),
             ("8", GREEN, "选项行外观：flag 变药丸徽章、输入框圆角细边框、行距拉开"),
             ("C", RED, "控件类型映射不能换：TEXT→JTextField、NUMBER→JSpinner、SWITCH→JCheckBox、路径→输入框+按钮；参数区里不能出现 JComboBox"),
             ("D", RED, "工作目录两个图标的像素：必须含 0xF5A623 / 0x000000 / 0x00A4EF（±60），非透明像素 > 40"),
         ]),
    dict(file="ui-conn.png", native=1060, width=900,
         title="③ 连接配置窗口", sub="1060×760　真实渲染　vmadapt.ui.ConnectionPanel",
         zones=[
             ("9", GREEN, (6, 34, 1052, 348), (530, 90)),
             ("E", RED, (18, 300, 420, 338), (220, 319)),
         ],
         legend=[
             ("9", GREEN, "整个窗口：颜色、字体、内边距、边框、选项卡外观都可以改 —— 这个窗口没有任何测试断言组件树 / 尺寸 / 颜色"),
             ("E", RED, "按钮文案不能动：「测试连接」/「Test connection」被 merged\\build\\CrossModuleCheck.java 逐字断言"),
         ]),
]

# ---------------------------------------------------------------- measure
for r in ROWS:
    im = Image.open(os.path.join(D, r["file"])).convert("RGB")
    r["img"] = im.resize((r["width"], int(im.height * r["width"] / im.width)), Image.LANCZOS)
    r["sc"] = r["width"] / r["native"]
    h = 0
    for tag, color, zh in r["legend"]:
        h += len(wrap(zh, F(16), LEGW - 46)) * 23 + 13
    r["leg_h"] = h

CAN = [
    "所有颜色、字号、圆角、内边距、边框粗细 —— 没有任何测试断言（约 67 处内联样式，全在 bioenv\\ui 下）",
    "按钮外观：扁平化 + 主次分级；弹窗里的按钮也能统一（注册到 UIManager 的 ButtonUI）",
    "图标：环境与工具的彩色圆点、Tux / Windows 标记（但像素颜色与像素数不能动，见 D）",
    "顶栏重排：品牌区 + 分段控件 + 动作 + 语言开关（分隔条数量与位置约束仍要满足）",
    "命令预览改深色终端；输出区、状态栏、分区标题的配色与字体",
    "连接配置窗口（vmadapt.ui.ConnectionPanel）的整个外观 —— 它没有被任何测试锁住组件树 / 尺寸 / 颜色",
    "i18n 文案：在 Strings.java / UiText.java 里成对新增 key 并接上刷新回调即可（中英必须成对）",
]
CANNOT = [
    "分隔条：恰好 2 横 + 1 纵；粗细 ≥ 4px；树侧初始位置 120–400px；右侧 ≥ 300px；每条都要能拖动",
    "字段名与类型：modeBox(JComboBox, 0=WSL/1=Linux VM)、logArea(JTextArea)、statusLabel(JLabel)、applyStatus/applyLog(Runnable)",
    "方法名：setStatus(String) / appendLog(String) / appendLogRaw(String)",
    "setStatus 热路径：暖机后不得再触发任何类加载 —— 这条路径上不能加 lambda 或匿名类",
    "分区标题：三个 TitledBorder 的标题必须能从 i18n key 取到，且语言切换是改标题而不是换 border 对象",
    "选项行控件类型：TEXT→JTextField、NUMBER→JSpinner、SWITCH→JCheckBox；参数区里不能出现 JComboBox",
    "工作目录图标像素：含 0xF5A623 / 0x000000 / 0x00A4EF（±60），非透明像素 > 40，两个图标不能同实例",
    "setName 契约：bioenv.makeWorkdir / bioenv.chooseWorkdir / bioenv.subcommand / bioenv.editor.* 等 —— 测试唯一的抓手",
    "插件入口：project.json 固定 bioenv.ui.MainPanel；PluginInject 的 generatePanel() / getPluginName() 签名",
    "构建：JDK 11（class 主版本 55）；33 个回归测试 + JAR 暂存门禁 + MainPanel$N 匿名类完整性门禁必须全绿",
]

half = (W - 3 * PAD) // 2
box_h = 62 + max(
    sum(len(wrap(t, F(16), half - 46)) * 22 + 12 for t in CAN),
    sum(len(wrap(t, F(16), half - 46)) * 22 + 12 for t in CANNOT)) + 16

total = HEAD + sum(CAP + max(r["img"].height, r["leg_h"]) + GAP for r in ROWS) + box_h + 60
img = Image.new("RGB", (W, total), (255, 255, 255))
d = ImageDraw.Draw(img)

d.rectangle([0, 0, W, HEAD - 3], fill=(0xFA, 0xFA, 0xFB))
d.rectangle([0, HEAD - 3, W, HEAD], fill=ACCENT)
d.text((PAD, 22), "BioEnv Caller  ·  UI 可改动边界图", font=F(35, True), fill=INK)
d.text((PAD, 72), "基线：虚拟机适配\\merged（2026-10-03 构建）　·　界面是真机离屏渲染，非示意图　·　"
                  "判定标准只有一条：merged\\build.ps1 跑的那 33 个回归测试会不会红", font=F(16), fill=MUTED)
d.text((PAD, 99), "绿框 = 纯外观，改了不该影响行为　　红框 / 红线 = 改了会挂", font=F(16, True), fill=SUB)

y = HEAD
for r in ROWS:
    d.rectangle([PAD, y, PAD + IMGW, y + CAP - 8], fill=ACCENT)
    d.text((PAD + 14, y + 9), r["title"], font=F(21, True), fill=(255, 255, 255))
    d.text((PAD + 28 + d.textlength(r["title"], font=F(21, True)), y + 14), r["sub"], font=F(14), fill=(255, 255, 255))

    im = r["img"]
    iy = y + CAP
    ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    for tag, color, box, bpos in r["zones"]:
        sc = r["sc"]
        b = [int(box[0] * sc), int(box[1] * sc), int(box[2] * sc), int(box[3] * sc)]
        od.rectangle(b, fill=GREEN_F if color == GREEN else RED_F)
        dashed(od, b, color + (255,))
    img.paste(Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB"), (PAD, iy))
    for tag, color, box, bpos in r["zones"]:
        badge(d, PAD + int(bpos[0] * r["sc"]), iy + int(bpos[1] * r["sc"]), tag, color)

    ly = y + 2
    for tag, color, zh in r["legend"]:
        ls = wrap(zh, F(16), LEGW - 46)
        badge(d, LEGX + 15, ly + 12, tag, color, r=14)
        for j, ln in enumerate(ls):
            d.text((LEGX + 38, ly + j * 23), ln, font=F(16), fill=SUB)
        ly += len(ls) * 23 + 13

    y += CAP + max(im.height, r["leg_h"]) + GAP

bx1, bx2 = PAD, PAD * 2 + half
d.rectangle([bx1, y, bx1 + half, y + box_h], outline=GREEN, width=2)
d.rectangle([bx2, y, bx2 + half, y + box_h], outline=RED, width=2)
d.rectangle([bx1, y, bx1 + half, y + 44], fill=(0xEC, 0xFC, 0xF5))
d.rectangle([bx2, y, bx2 + half, y + 44], fill=(0xFD, 0xEC, 0xEA))
d.text((bx1 + 16, y + 11), "能改 —— 纯外观层，构建不会红", font=F(21, True), fill=GREEN)
d.text((bx2 + 16, y + 11), "改不了 —— 被回归测试或插件契约锁住", font=F(21, True), fill=RED)
for col, items, colr in ((bx1, CAN, GREEN), (bx2, CANNOT, RED)):
    ty = y + 58
    for t in items:
        ls = wrap(t, F(16), half - 46)
        d.ellipse([col + 18, ty + 5, col + 26, ty + 13], fill=colr)
        for j, ln in enumerate(ls):
            d.text((col + 34, ty + j * 22), ln, font=F(16), fill=SUB)
        ty += len(ls) * 22 + 12

out = os.path.join(D, "BioEnvCaller-UI-可改动边界图.png")
img.save(out)
print("saved", out, img.size)
