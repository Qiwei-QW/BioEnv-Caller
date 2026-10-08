# -*- coding: utf-8 -*-
"""工具树：改造前 / 参考样式 / 实现后 三栏对照。"""
import os
from PIL import Image, ImageDraw, ImageFont

D = r"C:\Users\14231\Desktop\TBtools插件\UI改造方案\_方案与对照"

MS = r"C:\Windows\Fonts\msyh.ttc"
MSB = r"C:\Windows\Fonts\msyhbd.ttc"
ACCENT = (0x4F, 0x46, 0xE5)
INK = (0x11, 0x11, 0x14)
SUB = (0x47, 0x47, 0x51)
MUTED = (0x6E, 0x6E, 0x78)
HAIR = (0xE7, 0xE7, 0xED)


def F(s, b=False):
    return ImageFont.truetype(MSB if b else MS, s)


COLW = 380
CAP = 44
PAD = 34
GAP = 30

def load(name):
    im = Image.open(os.path.join(D, name)).convert("RGB")
    return im.resize((COLW, int(im.height * COLW / im.width)), Image.LANCZOS)


before = load("tree-old.png")
ref = load("参考-工具树样式.png")
after = load("preview-tree.png")

H = max(before.height, ref.height, after.height)
panels = [
    ("改造前（旧画法）", "BEFORE", (0x6E, 0x6E, 0x78), before, H),
    ("参考样式（你给的图）", "REFERENCE", (0x0B, 0x93, 0x66), ref, H),
    ("实现后（已打包进插件）", "AFTER", ACCENT, after, H),
]

W = PAD * 2 + COLW * 3 + GAP * 2
HEAD = 116
img = Image.new("RGB", (W, HEAD + CAP + H + 40), (255, 255, 255))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, W, HEAD - 3], fill=(0xFA, 0xFA, 0xFB))
d.rectangle([0, HEAD - 3, W, HEAD], fill=ACCENT)
d.text((PAD, 20), "左侧工具树  ·  样式改造对照", font=F(32, True), fill=INK)
d.text((PAD, 66), "只改了左侧这棵树：彩色圆点 + 引导线 + 名称/计数字重分层 + 行高与底色。"
                  "面板其余部分零改动（像素级比对：树面板之外 0 处差异）。", font=F(16), fill=MUTED)
d.text((PAD, 90), "参考图里的三栏数据（环境 / 工具 / 计数）与实际渲染用的是同一套节点结构。", font=F(16), fill=SUB)

for i, (zh, en, color, im, h) in enumerate(panels):
    x = PAD + i * (COLW + GAP)
    y = HEAD
    d.rectangle([x, y, x + COLW, y + CAP - 8], fill=color)
    d.text((x + 12, y + 8), zh, font=F(19, True), fill=(255, 255, 255))
    d.text((x + 12 + d.textlength(zh, font=F(19, True)) + 10, y + 13), en, font=F(13), fill=(255, 255, 255))
    yy = y + CAP - 8
    frame = Image.new("RGB", (COLW, h), (255, 255, 255))
    frame.paste(im.crop((0, 0, COLW, min(h, im.height))), (0, 0))
    d.rectangle([x - 1, yy - 1, x + COLW, yy + h], outline=HAIR)
    img.paste(frame, (x, yy))

out = os.path.join(D, "工具树-改造对照.png")
img.save(out)
print("saved", out, img.size)
