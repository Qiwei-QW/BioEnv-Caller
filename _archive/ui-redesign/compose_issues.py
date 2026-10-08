# -*- coding: utf-8 -*-
"""把"哪几处还是原生的"标出来。"""
import os
from PIL import Image, ImageDraw, ImageFont

D = r"C:\Users\14231\Desktop\TBtools插件\UI改造方案\_方案与对照"
MS, MSB = r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"
RED = (0xD9, 0x2D, 0x2D)
INK, SUB, MUTED = (0x11, 0x11, 0x14), (0x47, 0x47, 0x51), (0x6E, 0x6E, 0x78)
ACCENT = (0x4F, 0x46, 0xE5)


def F(s, b=False):
    return ImageFont.truetype(MSB if b else MS, s)


def load(n, w):
    im = Image.open(os.path.join(D, n)).convert("RGB")
    return im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)


W, PAD, HEAD, CW, CAP = 1840, 40, 132, 860, 36
dark = load("repro-dark.png", CW)
per = load("repro-personal.png", CW)

# 逻辑坐标(1400x900) → 面板坐标
SX = CW / 1400.0


def box(d, ox, oy, x0, y0, x1, y1, n):
    r = [ox + x0 * SX, oy + y0 * SX, ox + x1 * SX, oy + y1 * SX]
    d.rounded_rectangle(r, radius=6, outline=RED, width=3)
    cx, cy = r[0] - 13, r[1] - 13
    d.ellipse([cx, cy, cx + 26, cy + 26], fill=RED)
    d.text((cx + 8, cy + 4), str(n), font=F(15, True), fill=(255, 255, 255))


CAN_H = HEAD + CAP + dark.height + 40 + CAP + per.height + 60 + 200
img = Image.new("RGB", (W, CAN_H), (255, 255, 255))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, W, HEAD - 3], fill=(0xFA, 0xFA, 0xFB))
d.rectangle([0, HEAD - 3, W, HEAD], fill=RED)
d.text((PAD, 18), "问题定位：主题只刷了一半", font=F(32, True), fill=INK)
d.text((PAD, 64), "下面两张是插件里的真实渲染（左列是工具树，中间是参数表单）。红色圈出来的地方，还是系统原生的样子，没有被主题接管。",
       font=F(15), fill=MUTED)
d.text((PAD, 94), "一个界面上「新造型」和「原生控件」混着，比全用原生还难看 —— 这就是它难看的根本原因。",
       font=F(15, True), fill=SUB)

y = HEAD
# 深色
d.rectangle([PAD, y, PAD + CW, y + CAP - 6], fill=(0x2B, 0x2B, 0x36))
d.text((PAD + 14, y + 6), "深色", font=F(18, True), fill=(255, 255, 255))
d.text((PAD + 14 + d.textlength("深色", font=F(18, True)) + 12, y + 10),
       "最刺眼：浅色原生控件压在深色卡片上", font=F(13), fill=(255, 255, 255))
oy = y + CAP - 6
img.paste(dark, (PAD, oy))
box(d, PAD, oy, 284, 228, 1052, 470, 1)      # 选项区灰底
box(d, PAD, oy, 372, 286, 1040, 320, 2)      # 输入框
box(d, PAD, oy, 1032, 250, 1048, 600, 3)     # 滚动条
box(d, PAD, oy, 284, 612, 1052, 806, 4)      # 附加参数
box(d, PAD, oy, 284, 845, 1052, 900, 5)      # 命令预览

y = oy + dark.height + 40
d.rectangle([PAD, y, PAD + CW, y + CAP - 6], fill=ACCENT)
d.text((PAD + 14, y + 6), "个性", font=F(18, True), fill=(255, 255, 255))
d.text((PAD + 14 + d.textlength("个性", font=F(18, True)) + 12, y + 10),
       "同样的几处：底色是灰的，卡片是白的，谁也不挨着谁", font=F(13), fill=(255, 255, 255))
oy2 = y + CAP - 6
img.paste(per, (PAD, oy2))
box(d, PAD, oy2, 284, 228, 1052, 470, 1)
box(d, PAD, oy2, 372, 286, 1040, 320, 2)
box(d, PAD, oy2, 1032, 250, 1048, 600, 3)
box(d, PAD, oy2, 284, 612, 1052, 806, 4)
box(d, PAD, oy2, 284, 845, 1052, 900, 5)

# 图例
ly = oy2 + per.height + 26
items = [
    ("1", "选项区：还是原生面板灰。它是一片不透明的容器，把底下已经刷成卡片色的表单整个盖住了。"),
    ("2", "每一行的输入框：系统原生白底方框。我只给「工作目录」和「查找」刷了色，逐行的控件没管。"),
    ("3", "滚动条：原生灰立体条。深色下最突兀的就是它。"),
    ("4", "「附加参数」文本域：同一个问题，没被接管。"),
    ("5", "命令预览：底色对了，但仍是方角、贴合在灰底上，跟卡片造型对不上。"),
    ("+", "还有你没截到的：类型下拉框（JComboBox）、勾选框、数字框、三个分区之间的间距与圆角 —— 全都没动。"),
]
for n, s in items:
    d.ellipse([PAD + 4, ly + 3, PAD + 24, ly + 23], fill=RED if n != "+" else MUTED)
    d.text((PAD + 9, ly + 5), n, font=F(13, True), fill=(255, 255, 255))
    d.text((PAD + 34, ly + 3), s, font=F(14), fill=SUB)
    ly += 26

out = os.path.join(D, "风格切换-问题定位.png")
img.crop((0, 0, W, min(CAN_H, ly + 20))).save(out)
print("saved", out)
