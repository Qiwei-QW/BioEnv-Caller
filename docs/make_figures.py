# -*- coding: utf-8 -*-
"""把 TBtools 截图做成带红框/箭头/文字的说明图。

坐标一律用「预览图坐标」——也就是看截图时看到的 1610×1006 那一套，
脚本内部换算成原图（2880×1800）像素。
"""
import os
from PIL import Image, ImageDraw, ImageFont

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(BASE, "build", "shots")
OUT = os.path.join(BASE, "docs", "img")
os.makedirs(OUT, exist_ok=True)

S = 2880.0 / 1610.0
RED = (200, 16, 16)
DARK = (150, 8, 8)
WHITE = (255, 255, 255)

FONT = r"C:\Windows\Fonts\msyhbd.ttc"
f_title = ImageFont.truetype(FONT, 44)
f_label = ImageFont.truetype(FONT, 36)


def figure(name, shot, crop, labels, title, out_w=1600, margin=640):
    """crop 与 labels 都用预览坐标。"""
    src = Image.open(os.path.join(SHOTS, shot)).convert("RGB")
    px0, py0, px1, py1 = [int(v * S) for v in crop]
    px0, py0 = max(0, px0), max(0, py0)
    px1, py1 = min(src.width, px1), min(src.height, py1)
    body = src.crop((px0, py0, px1, py1))
    bw, bh = body.size

    head = 78
    canvas = Image.new("RGB", (bw + margin, bh + head), (252, 252, 254))
    canvas.paste(body, (0, head))
    d = ImageDraw.Draw(canvas)
    d.text((20, 16), title, font=f_title, fill=(25, 25, 30))

    # 标签贴着各自的框放（上下对齐），再往下推避免重叠
    labels = sorted(labels, key=lambda l: (l[0][1] + l[0][3]))
    rows = []
    for rect, text in labels:
        lines = text.split("\n")
        h = 24 + len(lines) * 46
        rcx = int(rect[0] * S) - px0
        rcy0 = int(rect[1] * S) - py0
        rcy1 = int(rect[3] * S) - py0
        rcx1 = int(rect[2] * S) - px0
        want = (rcy0 + rcy1) // 2 + head - h // 2
        rows.append([rcx, rcy0, rcx1, rcy1, text, lines, h, want])
    prev_bottom = -10 ** 6
    for r in rows:
        r[7] = max(r[7], prev_bottom + 8)
        prev_bottom = r[7] + r[6]
    over = prev_bottom - (bh + head - 8)
    if over > 0:
        for r in rows:
            r[7] -= over

    for rcx, rcy0, rcx1, rcy1, text, lines, h, ly in rows:
        d.rectangle([rcx, rcy0 + head, rcx1, rcy1 + head], outline=RED, width=4)
        tw = max(d.textlength(s_, font=f_label) for s_ in lines)
        lx = bw + 22
        d.rounded_rectangle([lx, ly, lx + tw + 30, ly + h], radius=12, fill=DARK)
        for k, s_ in enumerate(lines):
            d.text((lx + 15, ly + 11 + k * 46), s_, font=f_label, fill=WHITE)

        ay = ly + 22
        target = rcy0 + (rcy1 - rcy0) // 2 + head
        d.line([(lx, ay), (rcx1, ay)], fill=RED, width=3)
        d.line([(rcx1, ay), (rcx1, target)] if abs(ay - target) > 4 else
               [(lx, ay), (rcx1, target)], fill=RED, width=3)
        d.ellipse([rcx1 - 6, target - 6, rcx1 + 6, target + 6], fill=RED)

    canvas = canvas.resize((out_w, int(canvas.height * out_w / canvas.width)), Image.LANCZOS)
    canvas.save(os.path.join(OUT, name), "PNG", optimize=True)
    print("wrote", name, canvas.size)


# 1 主界面
figure("01-overview.png", "main-screen.png", (0, 82, 1600, 950), [
    ((6, 88, 632, 910), "图标栏 + 工具树\n（按 conda 环境分组）"),
    ((638, 88, 1600, 715), "参数区：从帮助原文生成"),
    ((638, 718, 1600, 910), "输出面板"),
    ((4, 916, 1600, 944), "状态栏：左提示 · 右当前连接"),
], "图 1 · 主界面总览")

# 2 参数区细节
figure("02-form.png", "main-screen.png", (628, 82, 1600, 720), [
    ((642, 98, 1150, 124), "当前工具 @ 环境 — 参数个数"),
    ((1368, 96, 1590, 126), "工具助手 / 编辑参数…"),
    ((642, 142, 1590, 178), "工作目录（右边两个按钮）"),
    ((642, 208, 1450, 242), "查找 / 在新窗口中打开"),
    ((642, 246, 1590, 528), "参数表：类型徽章 + 控件"),
    ((642, 532, 1590, 612), "附加参数（每行一个）"),
    ((642, 626, 1590, 672), "命令预览（可直接编辑）"),
    ((642, 678, 1010, 712), "运行 / 交互运行 / 终端 / 停止"),
], "图 2 · 参数区：自动生成，类型可改")

# 3 AI 面板
figure("03-ai.png", "ai-screen.png", (45, 85, 648, 912), [
    ((60, 96, 642, 130), "会话：下拉 / 新建 / 删除 / 导出"),
    ((60, 136, 642, 696), "对话区"),
    ((58, 700, 642, 890), "输入框：⊕ · 模型胶囊 · 发送"),
    ((60, 892, 642, 908), "用量：上下文 / 轮数 / tok/s / 累计"),
], "图 3 · AI 助手面板")

# 4 切换模型
figure("04-model-pop.png", "ai-pop.png", (40, 690, 650, 915), [
    ((352, 714, 556, 742), "TBmodel（TBtools 自带）"),
    ((352, 752, 556, 782), "自定义模型（高亮=当前）"),
    ((352, 797, 556, 824), "管理模型…（打开设置）"),
], "图 4 · 切换模型 / 进设置")

# 5 设置-连接
figure("05-settings-conn.png", "settings-conn.png", (380, 200, 1215, 470), [
    ((398, 246, 548, 382), "四个分页"),
    ((550, 310, 1212, 342), "连接方式：WSL / SSH 远程"),
    ((550, 374, 1212, 406), "WSL 发行版 / 已保存配置"),
    ((550, 424, 1212, 460), "扫描 conda 环境"),
], "图 5 · 设置 → 连接")

# 6 设置-外观
figure("06-settings-look.png", "settings-look.png", (380, 195, 1230, 400), [
    ((398, 246, 548, 382), "分页：外观 / 语言 / 模型"),
    ((550, 283, 782, 320), "经典 / 浅色 / 深色"),
], "图 6 · 设置 → 界面风格（语言页同构）")

# 7 设置-模型列表
figure("07-settings-model.png", "settings-model.png", (380, 195, 1230, 400), [
    ((550, 278, 1040, 320), "模型列表（绿点=配置完整）"),
    ((1085, 278, 1205, 320), "编辑 / 删除"),
    ((565, 325, 700, 358), "＋ 添加模型"),
], "图 7 · 设置 → 模型")

# 8 模型编辑对话框
figure("08-model-dialog.png", "model-dialog.png", (495, 248, 1120, 775), [
    ((508, 275, 1105, 350), "模型名称 / 显示名称"),
    ((508, 408, 1105, 452), "接口地址"),
    ((508, 455, 1105, 522), "API key（默认遮挡）+ 显示"),
    ((508, 548, 1105, 680), "最大输入 / 最大输出（token）"),
    ((955, 730, 1100, 762), "保存 / 取消"),
], "图 8 · 编辑模型对话框")

# 9 工具助手
figure("09-assistant.png", "tool-assistant.png", (300, 145, 1300, 875), [
    ((308, 180, 505, 212), "两个标签页"),
    ((318, 216, 1295, 800), "帮助原文（一字不改）"),
    ((1120, 828, 1290, 862), "保存 / 取消"),
], "图 9 · 工具助手")

# 10 编辑参数
figure("10-edit-params.png", "edit-params.png", (300, 140, 1300, 900), [
    ((305, 238, 470, 268), "＋ 添加参数"),
    ((475, 238, 600, 268), "重置此工具"),
    ((310, 285, 1292, 830), "表格：拖动排序 / 改类型 / 改描述"),
    ((310, 845, 900, 880), "底部：变更摘要"),
    ((1095, 843, 1295, 882), "确定 / 取消"),
], "图 10 · 编辑参数")
