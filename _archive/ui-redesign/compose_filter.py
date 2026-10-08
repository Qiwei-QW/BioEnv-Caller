# -*- coding: utf-8 -*-
"""过滤 → 工具检索：位置 + 名称的对照图。"""
import os
from PIL import Image, ImageDraw, ImageFont

D = r"C:\Users\14231\Desktop\TBtools插件\UI改造方案\_方案与对照"
MS, MSB = r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"
ACCENT = (0x4F, 0x46, 0xE5)
INK, SUB, MUTED = (0x11, 0x11, 0x14), (0x47, 0x47, 0x51), (0x6E, 0x6E, 0x78)
RED = (0xC0, 0x2A, 0x2A)
OK = (0x0B, 0x93, 0x66)


def F(s, b=False):
    return ImageFont.truetype(MSB if b else MS, s)


before = Image.open(os.path.join(D, "现状-原始界面.png")).convert("RGB")
after = Image.open(os.path.join(D, "after-layout.png")).convert("RGB")

W = 1320
IW = 1180
b1 = before.crop((595, 140, 2470, 210))          # 工具栏里的「过滤」
a1 = after.crop((0, 4, 1400, 40))                # 新的工具栏（已经没有它）
a2 = after.crop((0, 36, 300, 150))               # 工具树标题 + 工具检索

b1 = b1.resize((IW, int(IW * b1.height / b1.width)), Image.LANCZOS)
a1 = a1.resize((IW, int(IW * a1.height / a1.width)), Image.LANCZOS)
a2 = a2.resize((int(a2.width * 2.4), int(a2.height * 2.4)), Image.LANCZOS)

H = 118 + b1.height + 46 + a1.height + 16 + a2.height + 120
img = Image.new("RGB", (W, H), (255, 255, 255))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, W, 92], fill=(0xFA, 0xFA, 0xFB))
d.rectangle([0, 92, W, 95], fill=ACCENT)
d.text((30, 16), "「过滤」→「工具检索」：位置 + 名称", font=F(30, True), fill=INK)
d.text((30, 58), "从顶部工具条搬到左侧工具树标题下面，并改名（中文：工具检索，英文：Tool search）。外观没动。",
       font=F(15), fill=MUTED)

y = 118
d.text((30, y), "改造前", font=F(19, True), fill=RED)
d.text((110, y + 3), "工具条右侧的「过滤」+ 输入框", font=F(15), fill=SUB)
y += 28
img.paste(b1, (30, y))
d.rectangle([30, y, 30 + IW, y + b1.height], outline=RED, width=3)
y += b1.height + 46

d.text((30, y), "改造后", font=F(19, True), fill=OK)
d.text((110, y + 3), "工具条上没有它了（语言按钮仍在最右）", font=F(15), fill=SUB)
y += 28
img.paste(a1, (30, y))
y += a1.height + 16
img.paste(a2, (30, y))
d.rectangle([30, y, 30 + a2.width, y + a2.height], outline=OK, width=3)
d.text((30 + a2.width + 24, y + 16), "「工具检索」在这儿：", font=F(17, True), fill=INK)
d.text((30 + a2.width + 24, y + 44), "工具树标题下面、树上面，", font=F(15), fill=SUB)
d.text((30 + a2.width + 24, y + 68), "不跟着树一起滚动。", font=F(15), fill=SUB)
d.text((30 + a2.width + 24, y + 100), "筛的就是这棵树，", font=F(15), fill=SUB)
d.text((30 + a2.width + 24, y + 124), "贴着树一眼就明白范围。", font=F(15), fill=SUB)

out = os.path.join(D, "工具检索-位置对照.png")
img.save(out)
print("saved", out, img.size)
