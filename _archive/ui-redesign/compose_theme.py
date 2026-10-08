# -*- coding: utf-8 -*-
"""UI 风格切换：设计提案板。左=现状（真实截图），右上=现代，右下=深色。"""
import os
from PIL import Image, ImageDraw, ImageFont

D = r"C:\Users\14231\Desktop\TBtools插件\UI改造方案\_方案与对照"
MOCK = os.path.join(D, "mock")
MS, MSB = r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"

ACCENT = (0x4F, 0x46, 0xE5)
ACCENT_SOFT = (0xEE, 0xEF, 0xFF)
INK, SUB, MUTED = (0x11, 0x11, 0x14), (0x47, 0x47, 0x51), (0x6E, 0x6E, 0x78)
OK, WARN, DANGER = (0x0B, 0x93, 0x66), (0xA8, 0x64, 0x0A), (0xB4, 0x23, 0x23)
WARN_SOFT = (0xFF, 0xF7, 0xED)
HAIR = (0xE7, 0xE7, 0xED)


def F(s, b=False):
    return ImageFont.truetype(MSB if b else MS, s)


def load(p, w):
    im = Image.open(p).convert("RGB")
    return im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)


W, PAD, HEAD = 1840, 40, 150
CW = 860                      # 每个面板宽
before = load(os.path.join(D, "现状-最新界面.png"), CW)
modern = load(os.path.join(MOCK, "modern.png"), CW)
dark = load(os.path.join(MOCK, "dark.png"), CW)

# 主题切换器特写（从 2 倍图里裁顶栏右侧）
m2 = Image.open(os.path.join(MOCK, "modern.png")).convert("RGB")
switcher = m2.crop((2352, 22, 2604, 98))
switcher = switcher.resize((int(switcher.width * 2.0), int(switcher.height * 2.0)), Image.LANCZOS)

CAP = 40
R1H = max(before.height, modern.height)
R2H = max(dark.height, 520)
CAN_H = HEAD + CAP + R1H + 34 + CAP + R2H + 40 + 300
img = Image.new("RGB", (W, CAN_H), (255, 255, 255))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, W, HEAD - 3], fill=(0xFA, 0xFA, 0xFB))
d.rectangle([0, HEAD - 3, W, HEAD], fill=ACCENT)
d.text((PAD, 20), "UI 风格切换  ·  设计提案", font=F(34, True), fill=INK)
d.text((PAD, 68), "同一个插件，三套画法。左上是现在的样子，右上是提案的「现代」风，右下是同一套的「深色」。"
                  "布局、控件、功能都不变 —— 变的只是它们怎么画。", font=F(16), fill=MUTED)
d.text((PAD, 104), "顶栏右侧新增风格切换器：点一下即切换，立即生效并记住选择。", font=F(16, True), fill=SUB)

y = HEAD


def panel(x, py, im, title, sub, color):
    d.rectangle([x, py, x + CW, py + CAP - 6], fill=color)
    d.text((x + 14, py + 8), title, font=F(18, True), fill=(255, 255, 255))
    d.text((x + 14 + d.textlength(title, font=F(18, True)) + 12, py + 12), sub, font=F(13), fill=(255, 255, 255))
    d.rectangle([x, py + CAP - 6, x + CW, py + CAP - 6 + im.height], outline=HAIR)
    img.paste(im, (x, py + CAP - 6))


panel(PAD, y, before, "现状", "BEFORE · 经典风", MUTED)
panel(W - PAD - CW, y, modern, "提案 A", "现代 · 浅色", ACCENT)
y += CAP + R1H + 34

panel(PAD, y, dark, "提案 B", "现代 · 深色（同一套，换配色）", (0x2B, 0x2B, 0x36))

# 右侧：切换器特写 + 色板 + 说明
rx = W - PAD - CW
d.rectangle([rx, y, rx + CW, y + R2H], outline=HAIR)
ty = y + 18
d.text((rx + 20, ty), "① 风格切换器（放在顶栏右侧，语言按钮旁边）", font=F(17, True), fill=INK)
ty += 30
img.paste(switcher, (rx + 20, ty))
d.rectangle([rx + 20, ty, rx + 20 + switcher.width, ty + switcher.height], outline=HAIR)
ty += switcher.height + 12
d.text((rx + 20, ty), "三段：经典 / 现代 / 深色。点一下就换，不用重启、不用重开面板。", font=F(14), fill=SUB)
ty += 34

d.text((rx + 20, ty), "② 两套色板", font=F(17, True), fill=INK)
ty += 28
sw = 88
tokens = [("底 #F3F3F7", (0xF3, 0xF3, 0xF7)), ("卡片 #FFFFFF", (255, 255, 255)),
          ("线 #E7E7EE", (0xE7, 0xE7, 0xEE)), ("主色 #4F46E5", ACCENT),
          ("主色浅 #EEF0FF", ACCENT_SOFT), ("正文 #15151A", (0x15, 0x15, 0x1A)),
          ("次要 #63636F", (0x63, 0x63, 0x6F)), ("终端 #0E1526", (0x0E, 0x15, 0x26))]
tokens2 = [("底 #101014", (0x10, 0x10, 0x14)), ("卡片 #191920", (0x19, 0x19, 0x20)),
           ("线 #2B2B36", (0x2B, 0x2B, 0x36)), ("主色 #8B84FF", (0x8B, 0x84, 0xFF)),
           ("主色浅 #22203C", (0x22, 0x20, 0x3C)), ("正文 #EFEFF5", (0xEF, 0xEF, 0xF5)),
           ("次要 #A0A0AE", (0xA0, 0xA0, 0xAE)), ("终端 #0A0F1C", (0x0A, 0x0F, 0x1C))]
for row_i, row in enumerate((tokens, tokens2)):
    for i, (lab, c) in enumerate(row):
        x = rx + 20 + i * (sw + 8)
        yy = ty + row_i * 62
        d.rounded_rectangle([x, yy, x + sw, yy + 26], radius=6, fill=c, outline=HAIR)
        d.text((x, yy + 30), lab, font=F(11), fill=MUTED)
ty += 128

d.text((rx + 20, ty), "③ 怎么实现 / 代价", font=F(17, True), fill=INK)
ty += 28
notes = [
    "做法：加一个 Theme 层，把散落各处的颜色/字体/边框收进去；切换 = 换一份值再重画。",
    "控件实例、名字、事件、布局一律不动 —— 33 个回归测试照旧约束。",
    "「经典」= 现在这套 Swing 原生外观，原样保留，随时能切回来。",
    "代价：要做到截图这种程度，按钮/输入框/滚动条/下拉框得自己画一遍（Swing 原生的方角灰边改不了）。",
    "如果只想先小步走：第一版只做「配色 + 字体 + 间距」，控件形状保持原生。",
]
for s in notes:
    d.ellipse([rx + 22, ty + 5, rx + 30, ty + 13], fill=ACCENT)
    d.text((rx + 38, ty), s, font=F(13.5), fill=SUB)
    ty += 24

out = os.path.join(D, "UI风格切换-设计提案.png")
img.crop((0, 0, W, min(CAN_H, ty + 30))).save(out)
print("saved", out)
