# -*- coding: utf-8 -*-
"""风格切换：实现后的三套对照。"""
import os
from PIL import Image, ImageDraw, ImageFont

D = r"C:\Users\14231\Desktop\TBtools插件\UI改造方案\_方案与对照"
MS, MSB = r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"
ACCENT = (0x4F, 0x46, 0xE5)
ACCENT_SOFT = (0xEE, 0xEF, 0xFF)
INK, SUB, MUTED = (0x11, 0x11, 0x14), (0x47, 0x47, 0x51), (0x6E, 0x6E, 0x78)
OK = (0x0B, 0x93, 0x66)
HAIR = (0xE7, 0xE7, 0xED)


def F(s, b=False):
    return ImageFont.truetype(MSB if b else MS, s)


def load(n, w):
    im = Image.open(os.path.join(D, n)).convert("RGB")
    return im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)


W, PAD, HEAD, CW, CAP = 1840, 40, 140, 860, 38
classic = load("repro-classic.png", CW)
personal = load("repro-personal.png", CW)
dark = load("repro-dark.png", CW)
R1 = max(classic.height, personal.height)
R2 = dark.height

# 切换器特写
full = Image.open(os.path.join(D, "repro-personal.png")).convert("RGB")
sw = full.crop((2385, 22, 2620, 100))
sw = sw.resize((int(sw.width * 1.8), int(sw.height * 1.8)), Image.LANCZOS)

CAN_H = HEAD + CAP + R1 + 34 + CAP + R2 + 40 + 300
img = Image.new("RGB", (W, CAN_H), (255, 255, 255))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, W, HEAD - 3], fill=(0xFA, 0xFA, 0xFB))
d.rectangle([0, HEAD - 3, W, HEAD], fill=ACCENT)
d.text((PAD, 20), "方案 A  ·  完成", font=F(34, True), fill=INK)
d.text((PAD, 68), "第一步补零件（容器底色、输入框、下拉框、滚动条），第二步补造型（圆角卡片边界、圆角深色终端、卡片底色对比）。",
       font=F(16), fill=MUTED)
d.text((PAD, 100), "下面是同一份插件、同一个界面在三套风格下的真实渲染 —— 不是示意图。布局和功能完全一致。",
       font=F(16, True), fill=SUB)

y = HEAD


def panel(x, im, title, sub, color):
    d.rectangle([x, y, x + CW, y + CAP - 6], fill=color)
    d.text((x + 14, y + 7), title, font=F(18, True), fill=(255, 255, 255))
    d.text((x + 14 + d.textlength(title, font=F(18, True)) + 12, y + 11), sub, font=F(13), fill=(255, 255, 255))
    d.rectangle([x, y + CAP - 6, x + CW, y + CAP - 6 + im.height], outline=HAIR)
    img.paste(im, (x, y + CAP - 6))


panel(PAD, classic, "经典", "CLASSIC · 默认，和以前一模一样", MUTED)
panel(W - PAD - CW, personal, "个性", "PERSONAL · 浅色卡片 + 靛蓝主色", ACCENT)
y += CAP + R1 + 34
panel(PAD, dark, "深色", "DARK · 同一套画法，换一组取值", (0x2B, 0x2B, 0x36))

rx = W - PAD - CW
d.rectangle([rx, y, rx + CW, y + R2], outline=HAIR)
ty = y + 16
d.text((rx + 20, ty), "切换器就在顶栏右侧，语言按钮旁边", font=F(17, True), fill=INK)
ty += 28
img.paste(sw, (rx + 20, ty))
d.rectangle([rx + 20, ty, rx + 20 + sw.width, ty + sw.height], outline=HAIR)
ty += sw.height + 14
notes = [
    ("默认仍是「经典」", "不动外观，所有回归测试跑的就是原来那条路。"),
    ("切换只改画法", "控件、位置、事件、已填的值都不重建 —— 换个颜色不该把参数弄丢。"),
    ("选择会记住", "存在 %USERPROFILE%\\.TBtools\\BioEnvCaller\\theme.txt，和语言放一起。"),
    ("能来回切", "切回经典时，每个控件都会把原来的底色/字体/边框还回去。"),
    ("回归测试", "33 / 33 全绿；插件已重新打包。"),

]
for t, s in notes:
    d.ellipse([rx + 24, ty + 6, rx + 32, ty + 14], fill=OK)
    d.text((rx + 40, ty), t, font=F(14, True), fill=INK)
    d.text((rx + 40, ty + 20), s, font=F(13), fill=SUB)
    ty += 48

out = os.path.join(D, "方案A-完成.png")
img.crop((0, 0, W, min(CAN_H, ty + 20))).save(out)
print("saved", out)
