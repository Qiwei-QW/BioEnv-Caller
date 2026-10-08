# -*- coding: utf-8 -*-
"""中间面板布局改造：前 = 你给的真实截图，后 = 改完之后的真实渲染。两边都是真像素。"""
import os
from PIL import Image, ImageDraw, ImageFont

D = r"C:\Users\14231\Desktop\TBtools插件\UI改造方案\_方案与对照"
MS, MSB = r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"
ACCENT = (0x4F, 0x46, 0xE5)
ACCENT_SOFT = (0xEE, 0xEF, 0xFF)
INK, SUB, MUTED = (0x11, 0x11, 0x14), (0x47, 0x47, 0x51), (0x6E, 0x6E, 0x78)
OK, WARN = (0x0B, 0x93, 0x66), (0xB4, 0x23, 0x23)
WARN_SOFT = (0xFD, 0xEC, 0xEA)
HAIR = (0xE7, 0xE7, 0xED)


def F(s, b=False):
    return ImageFont.truetype(MSB if b else MS, s)


before = Image.open(os.path.join(D, "现状-原始界面.png")).convert("RGB").crop((512, 232, 2660, 1472))
after = Image.open(os.path.join(D, "after-layout.png")).convert("RGB").crop((266, 34, 1400, 880))

W, PAD, HEAD = 1840, 40, 146
SW = 860
b = before.resize((SW, int(SW * before.height / before.width)), Image.LANCZOS)
a = after.resize((SW, int(SW * after.height / after.width)), Image.LANCZOS)
PH = max(b.height, a.height)

CAN_H = HEAD + 44 + PH + 36 + 560
img = Image.new("RGB", (W, CAN_H), (255, 255, 255))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, W, HEAD - 3], fill=(0xFA, 0xFA, 0xFB))
d.rectangle([0, HEAD - 3, W, HEAD], fill=ACCENT)
d.text((PAD, 20), "中间面板  ·  布局已改（只动位置）", font=F(34, True), fill=INK)
d.text((PAD, 68), "右边不是示意图 —— 它是改完之后，用真实的插件面板渲染出来的像素。风格、配色、字号、按钮样子全都没变。",
       font=F(16), fill=MUTED)
d.text((PAD, 102), "带圈数字 = 挪动过的控件。构建结果：编译通过，33 / 33 回归测试全绿。",
       font=F(16, True), fill=SUB)


def badge(cx, cy, t, color=ACCENT, r=15):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color, outline=(255, 255, 255), width=3)
    f = F(16, True)
    d.text((cx - d.textlength(t, font=f) / 2, cy - 11), t, font=f, fill=(255, 255, 255))


for i, (t, s, im, marks) in enumerate([
        ("改造前", "BEFORE（你给的截图）", b, []),
        ("改造后", "AFTER（真实渲染）", a,
         [("1", 476, 20), ("2", 300, 40), ("3", 96, 80),
          ("4", 272, 126), ("5", 62, 622), ("6", 815, 26)])]):
    px = PAD if i == 0 else W - PAD - SW
    py = HEAD + 44
    d.rectangle([px, py, px + SW, py + 36], fill=MUTED if i == 0 else ACCENT)
    d.text((px + 14, py + 7), t, font=F(19, True), fill=(255, 255, 255))
    d.text((px + 14 + d.textlength(t, font=F(19, True)) + 10, py + 12), s, font=F(13), fill=(255, 255, 255))
    d.rectangle([px, py + 36, px + SW, py + 36 + PH], outline=HAIR)
    img.paste(im, (px, py + 36))
    for tag, mx, my in marks:
        if my < PH:
            badge(px + mx, py + 36 + my, tag)

sy = HEAD + 44 + PH + 36
half = (W - 3 * PAD) // 2
b1, b2 = PAD, PAD * 2 + half
BH = 540

MOVES = [
    "「查看帮助原文 / 编辑参数…」→ 工具名右侧同一行（原先在底部按钮行）",
    "状态行「orthofinder @ orthofinder_env — 5 个参数」→ 紧贴工具名下方",
    "「工作目录」标签单独一行，输入框下一行占满宽度，两个图标按钮留在右端",
    "搜索框（连「查找」标签）→ 「选项」框内那一行的右端",
    "「运行 / 停止」→ 只落在中间面板底部（原先横跨中间列 + 输出列）",
    "「清空日志」→ 与「清空」合并后只留这一个，放进「输出」框内右上角",
]
KEPT = [
    "配色、字号、边框、按钮样式、文案：一处未改（右边就是证明）",
    "「选项」TitledBorder 仍挂在参数区滚动面板上 —— LayoutTest 靠它定位",
    "分隔条仍是 2 横 + 1 纵，粗细与初始位置都没动",
    "参数行控件类型映射、bioenv.* 控件名、工作目录图标像素都没动",
    "编译通过；33 / 33 回归测试全绿；插件已重新打包",
]
NEXT = [
    "「附加参数」区按原样保留（没压矮）—— 要压矮说一声",
    "「复制」按钮没加 —— 参考图里有，要加也说一声",
]

d.rectangle([b1, sy, b1 + half, sy + BH], outline=HAIR)
d.rectangle([b2, sy, b2 + half, sy + BH], outline=HAIR)
d.rectangle([b1, sy, b1 + half, sy + 44], fill=ACCENT_SOFT)
d.rectangle([b2, sy, b2 + half, sy + 44], fill=WARN_SOFT)
d.text((b1 + 16, sy + 11), "挪动清单", font=F(21, True), fill=ACCENT)
d.text((b2 + 16, sy + 11), "没动的 / 下一步", font=F(21, True), fill=WARN)

ty = sy + 62
for i, s in enumerate(MOVES, 1):
    badge(b1 + 26, ty + 12, str(i), ACCENT, r=13)
    d.text((b1 + 48, ty), s, font=F(15), fill=SUB)
    ty += 34

ty = sy + 62
d.text((b2 + 16, ty), "没动的（风格 + 门禁）", font=F(16, True), fill=OK)
ty += 26
for s in KEPT:
    d.ellipse([b2 + 18, ty + 5, b2 + 26, ty + 13], fill=OK)
    d.text((b2 + 34, ty), s, font=F(14), fill=SUB)
    ty += 24
ty += 12
d.text((b2 + 16, ty), "下一步（等你一句话）", font=F(16, True), fill=WARN)
ty += 26
for s in NEXT:
    d.text((b2 + 18, ty), "?", font=F(14, True), fill=WARN)
    d.text((b2 + 34, ty), s, font=F(14), fill=SUB)
    ty += 24

out = os.path.join(D, "中间面板-布局改造图.png")
img.save(out)
print("saved", out, img.size)
