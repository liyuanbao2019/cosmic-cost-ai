from copy import copy
from pathlib import Path
import re
import sys

from docx import Document
from docx.shared import Pt
from docx.enum.text import WD_ALIGN_PARAGRAPH
from openpyxl import load_workbook
from openpyxl.styles import Alignment
from openpyxl.worksheet.datavalidation import DataValidation


sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "outputs"
OUTPUT_DIR.mkdir(exist_ok=True)

PROJECT_TITLE = "关于集客一线直通车新增通知公告功能模块的需求"
DOC_TITLE = "集客一线直通车新增通知公告功能模块需求说明书"
SYSTEM_NAME = "家宽运维支撑系统--集客一线直通车"
PRIMARY_MODULE = "集客一线直通车"
SECONDARY_MODULE = "通知公告"

OVERALL_DESC = (
    "本项目拟在家宽运维支撑系统--集客一线直通车中新增通知公告功能模块，"
    "用于支撑重要工作指示、紧急事务和系统通知的统一发布、审核、定向触达和接收确认。"
)
BUILD_GOAL = (
    "建设通知公告模块，支持公告发布、修改、删除、废止、追加、定向发布、发布前审核、"
    "APP 消息推送、短信通知、APP 端消息查看及已读未读状态区分，并支持通知类型配置。"
)
BUILD_NECESSITY = (
    "当前重要通知主要依赖工作群沟通和人工盯控，通知触达、阅读状态和审核留痕能力不足。"
    "通过新增通知公告模块，可拓宽通知渠道，提升重要通知及时性和强制性，减轻盯控人员压力，"
    "并提升整体工作协同效率。"
)


FUNCTIONS = [
    {
        "id": "REQ-001",
        "name": "公告发布",
        "third": "公告管理",
        "desc": "创建公告标题和内容，选择通知类型、发布范围和通知渠道，提交发布或提交审核。",
        "steps": [
            "创建人录入公告标题、公告内容、通知类型、接收范围和通知渠道。",
            "系统校验公告必填项、通知类型和发布范围。",
            "系统根据审核配置决定直接发布或进入待审核状态。",
            "发布后系统记录公告信息并返回处理结果。",
        ],
    },
    {
        "id": "REQ-002",
        "name": "公告修改",
        "third": "公告管理",
        "desc": "对未发布、被驳回或允许修改的公告进行内容调整，并保留修改记录。",
        "steps": [
            "创建人选择需修改的公告并进入编辑页面。",
            "系统读取公告原内容、状态和可修改权限。",
            "创建人调整标题、内容、通知类型或发布范围。",
            "系统保存修改后的公告信息并返回处理结果。",
        ],
    },
    {
        "id": "REQ-003",
        "name": "公告删除",
        "third": "公告管理",
        "desc": "对符合删除条件的公告执行删除处理，系统记录删除状态和操作痕迹。",
        "steps": [
            "创建人或管理员选择需要删除的公告。",
            "系统读取公告状态并校验删除权限。",
            "系统将公告更新为删除状态并记录操作信息。",
            "系统返回删除处理结果。",
        ],
    },
    {
        "id": "REQ-004",
        "name": "公告废止",
        "third": "公告管理",
        "desc": "对已发布但不再适用的公告进行废止处理，必要时通知已接收人员。",
        "steps": [
            "创建人或管理员选择需要废止的已发布公告。",
            "系统读取公告当前状态和接收人员范围。",
            "系统更新公告废止状态并记录废止原因。",
            "系统向相关接收人员发送废止提醒并返回处理结果。",
        ],
    },
    {
        "id": "REQ-005",
        "name": "公告追加",
        "third": "公告管理",
        "desc": "对已发布公告追加补充内容，保留原公告内容并形成追加记录。",
        "steps": [
            "创建人选择已发布公告并录入追加内容。",
            "系统读取原公告内容、接收范围和发布状态。",
            "系统保存追加内容和追加历史。",
            "系统向原接收范围发送追加提醒并返回处理结果。",
        ],
    },
    {
        "id": "REQ-006",
        "name": "定向发布",
        "third": "定向发布",
        "desc": "支持按指定人员、角色和区域选择公告接收对象，并形成可追溯的接收范围。",
        "steps": [
            "创建人选择人员、角色或区域作为公告接收范围。",
            "系统读取组织、角色、区域和人员信息。",
            "系统保存公告接收范围。",
            "系统返回接收对象清单和选择结果。",
        ],
    },
    {
        "id": "REQ-007",
        "name": "公告审核",
        "third": "公告审核",
        "desc": "支持公告发布前审核，审核人员角色可配置，审核通过后发布，驳回后保留原文并由创建人修改后重新提交。",
        "steps": [
            "审核人员打开待审核公告并提交审核意见。",
            "系统读取公告原文、审核角色配置和审核记录。",
            "系统保存审核结果，审核通过则进入可发布状态，驳回则保留原文。",
            "系统向创建人发送审核结果通知。",
        ],
    },
    {
        "id": "REQ-008",
        "name": "通知类型配置",
        "third": "参数配置",
        "desc": "支持配置公告通知类型，默认类型包括投诉故障催办、系统升级、系统故障、会议通知、指标预警和其他。",
        "steps": [
            "管理员维护通知类型名称、排序和启停状态。",
            "系统读取现有通知类型配置。",
            "系统保存通知类型配置结果。",
            "系统返回更新后的通知类型清单。",
        ],
    },
    {
        "id": "REQ-009",
        "name": "APP 消息查看",
        "third": "消息接收",
        "desc": "APP 端支持接收并查看公告消息，展示公告标题、内容、通知类型、发布时间和发布人。",
        "steps": [
            "接收人员在 APP 端打开通知公告消息。",
            "系统读取公告详情、接收记录和消息状态。",
            "系统记录消息查看时间和阅读状态。",
            "系统向 APP 端返回公告详情。",
        ],
    },
    {
        "id": "REQ-010",
        "name": "已读未读状态区分",
        "third": "消息接收",
        "desc": "APP 端根据接收人员阅读情况区分已读和未读状态，并支持状态更新。",
        "steps": [
            "接收人员进入公告消息列表或打开公告详情。",
            "系统读取接收人的公告阅读状态。",
            "系统更新公告为已读状态并记录阅读时间。",
            "系统返回最新已读未读状态。",
        ],
    },
]


MOVEMENTS = [
    {
        "req": "REQ-001",
        "user": "发起者：创建人\n接收者：集客一线直通车",
        "trigger": "点击公告发布或提交审核",
        "process": "公告发布",
        "rows": [
            ("接收公告发布信息", "E", "公告发布信息", "公告标题、公告内容、通知类型、接收范围、通知渠道"),
            ("读取通知类型及审核配置", "R", "公告配置", "通知类型编码、通知类型名称、审核角色、发布渠道"),
            ("保存公告发布或待审核记录", "W", "公告记录", "公告编号、标题、内容、状态、创建人、创建时间"),
            ("返回公告提交处理结果", "X", "处理结果", "公告编号、处理状态、提示信息"),
        ],
    },
    {
        "req": "REQ-002",
        "user": "发起者：创建人\n接收者：集客一线直通车",
        "trigger": "点击公告修改保存",
        "process": "公告修改",
        "rows": [
            ("接收公告修改信息", "E", "公告修改信息", "公告编号、修改标题、修改内容、通知类型、接收范围"),
            ("读取原公告信息和状态", "R", "公告记录", "公告编号、原标题、原内容、公告状态、创建人"),
            ("保存修改后的公告信息", "W", "公告记录", "公告编号、标题、内容、修改人、修改时间、状态"),
            ("返回公告修改处理结果", "X", "处理结果", "公告编号、处理状态、提示信息"),
        ],
    },
    {
        "req": "REQ-003",
        "user": "发起者：创建人或管理员\n接收者：集客一线直通车",
        "trigger": "点击公告删除",
        "process": "公告删除",
        "rows": [
            ("接收公告删除请求", "E", "公告删除信息", "公告编号、删除原因、操作人"),
            ("读取公告状态和权限信息", "R", "公告记录", "公告编号、公告状态、创建人、发布状态"),
            ("记录公告删除状态", "W", "公告记录", "公告编号、删除状态、删除人、删除时间"),
            ("返回公告删除处理结果", "X", "处理结果", "公告编号、处理状态、提示信息"),
        ],
    },
    {
        "req": "REQ-004",
        "user": "发起者：创建人或管理员\n接收者：集客一线直通车、公告接收人",
        "trigger": "点击公告废止",
        "process": "公告废止",
        "rows": [
            ("接收公告废止请求", "E", "公告废止信息", "公告编号、废止原因、操作人"),
            ("读取公告状态和接收范围", "R", "公告记录", "公告编号、公告状态、接收范围、发布时间"),
            ("记录公告废止状态", "W", "公告记录", "公告编号、废止状态、废止人、废止时间"),
            ("发送公告废止提醒", "X", "废止提醒消息", "公告编号、消息标题、接收人、发送渠道、发送状态"),
        ],
    },
    {
        "req": "REQ-005",
        "user": "发起者：创建人\n接收者：集客一线直通车、公告接收人",
        "trigger": "点击公告追加保存",
        "process": "公告追加",
        "rows": [
            ("接收公告追加内容", "E", "公告追加信息", "公告编号、追加内容、追加附件、操作人"),
            ("读取原公告信息和接收范围", "R", "公告记录", "公告编号、原内容、接收范围、公告状态"),
            ("保存公告追加记录", "W", "公告追加记录", "追加编号、公告编号、追加内容、追加人、追加时间"),
            ("发送公告追加提醒", "X", "追加提醒消息", "公告编号、消息标题、接收人、发送渠道、发送状态"),
        ],
    },
    {
        "req": "REQ-006",
        "user": "发起者：创建人\n接收者：集客一线直通车",
        "trigger": "选择人员、角色或区域",
        "process": "定向发布范围选择",
        "rows": [
            ("接收定向发布范围选择", "E", "接收范围信息", "公告编号、人员标识、角色标识、区域标识"),
            ("读取人员角色区域信息", "R", "组织权限信息", "人员编码、角色编码、区域编码、组织名称"),
            ("保存公告接收范围", "W", "公告接收范围", "公告编号、接收对象类型、接收对象编码、创建时间"),
            ("返回接收对象清单", "X", "接收对象清单", "公告编号、接收对象名称、接收对象类型、人数"),
        ],
    },
    {
        "req": "REQ-007",
        "user": "发起者：审核人员\n接收者：集客一线直通车、创建人",
        "trigger": "提交审核通过或驳回意见",
        "process": "公告审核",
        "rows": [
            ("接收公告审核意见", "E", "审核信息", "公告编号、审核结论、审核意见、审核人"),
            ("读取公告原文和审核配置", "R", "公告审核资料", "公告编号、公告标题、公告内容、审核角色、原文状态"),
            ("保存公告审核结果", "W", "审核记录", "审核编号、公告编号、审核结论、审核意见、审核时间"),
            ("通知创建人审核结果", "X", "审核结果消息", "公告编号、审核结论、驳回原因、接收人"),
        ],
    },
    {
        "req": "REQ-008",
        "user": "发起者：管理员\n接收者：集客一线直通车",
        "trigger": "维护通知类型配置",
        "process": "通知类型配置",
        "rows": [
            ("接收通知类型配置内容", "E", "通知类型配置", "类型编码、类型名称、排序号、启停状态"),
            ("读取现有通知类型", "R", "通知类型清单", "类型编码、类型名称、排序号、状态"),
            ("保存通知类型配置", "W", "通知类型清单", "类型编码、类型名称、排序号、更新人、更新时间"),
            ("返回通知类型配置结果", "X", "处理结果", "类型编码、处理状态、提示信息"),
        ],
    },
    {
        "req": "REQ-009",
        "user": "发起者：公告接收人\n接收者：APP端、集客一线直通车",
        "trigger": "打开 APP 公告消息",
        "process": "APP 消息查看",
        "rows": [
            ("接收 APP 公告查看请求", "E", "公告查看请求", "公告编号、接收人账号、终端标识"),
            ("读取公告详情和接收记录", "R", "公告消息", "公告编号、标题、内容、通知类型、发布时间、阅读状态"),
            ("记录公告查看状态", "W", "公告阅读记录", "公告编号、接收人账号、阅读状态、阅读时间"),
            ("返回 APP 公告详情", "X", "公告详情", "公告编号、标题、内容、通知类型、发布人、发布时间"),
        ],
    },
    {
        "req": "REQ-010",
        "user": "发起者：公告接收人\n接收者：APP端、集客一线直通车",
        "trigger": "进入消息列表或查看公告详情",
        "process": "已读未读状态更新",
        "rows": [
            ("接收消息状态查询或更新请求", "E", "消息状态请求", "公告编号、接收人账号、当前状态、操作时间"),
            ("读取消息阅读状态", "R", "公告阅读记录", "公告编号、接收人账号、阅读状态、接收时间"),
            ("更新公告阅读状态", "W", "公告阅读记录", "公告编号、接收人账号、阅读状态、阅读时间"),
            ("返回最新消息状态", "X", "消息状态结果", "公告编号、接收人账号、已读状态、未读数量"),
        ],
    },
]


def find_template(suffix: str, prefix: str):
    matches = [
        p for p in ROOT.glob(f"*{suffix}")
        if not p.name.startswith("~$") and p.name.startswith(prefix)
    ]
    if not matches:
        raise FileNotFoundError(f"未找到模板: {prefix}*{suffix}")
    return matches[0]


def set_paragraph_text(paragraph, text, bold=None):
    for run in paragraph.runs:
        run.text = ""
    run = paragraph.add_run(text)
    if bold is not None:
        run.bold = bold
    for r in paragraph.runs:
        if r.text:
            r.font.name = "宋体"
            r._element.rPr.rFonts.set(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}eastAsia",
                "宋体",
            )
            r.font.size = Pt(10.5)
    return paragraph


def first_paragraph(doc, exact_text):
    for p in doc.paragraphs:
        if p.text.strip() == exact_text:
            return p
    raise ValueError(f"未找到段落: {exact_text}")


def replace_first(doc, old_text, new_text):
    for p in doc.paragraphs:
        if p.text.strip() == old_text:
            set_paragraph_text(p, new_text)
            return True
    return False


def delete_between(doc, start_text, end_text, delete_start=False):
    body = doc.element.body
    children = list(body.iterchildren())
    start_idx = end_idx = None
    for i, child in enumerate(children):
        if child.tag.endswith("}p"):
            text = "".join(t.text for t in child.iter() if t.tag.endswith("}t") and t.text).strip()
            if text == start_text and start_idx is None:
                start_idx = i
            elif text == end_text and start_idx is not None:
                end_idx = i
                break
    if start_idx is None or end_idx is None:
        raise ValueError(f"未找到删除范围: {start_text} -> {end_text}")
    remove_from = start_idx if delete_start else start_idx + 1
    for child in children[remove_from:end_idx]:
        body.remove(child)


def insert_paragraph_before(target, text="", style=None, bold=None):
    p = target.insert_paragraph_before(text)
    if style:
        try:
            p.style = style
        except Exception:
            pass
    if p.runs:
        for r in p.runs:
            r.font.name = "宋体"
            r._element.rPr.rFonts.set(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}eastAsia",
                "宋体",
            )
            if bold is not None:
                r.bold = bold
            if style and "Heading" in style:
                r.font.size = Pt(12)
            else:
                r.font.size = Pt(10.5)
    return p


def insert_table_before(doc, target, headers, rows):
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = "Table Grid"
    for i, header in enumerate(headers):
        table.cell(0, i).text = header
    for row in rows:
        cells = table.add_row().cells
        for i, value in enumerate(row):
            cells[i].text = str(value)
    target._p.addprevious(table._tbl)
    for row in table.rows:
        for cell in row.cells:
            for p in cell.paragraphs:
                for r in p.runs:
                    r.font.name = "宋体"
                    r._element.rPr.rFonts.set(
                        "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}eastAsia",
                        "宋体",
                    )
                    r.font.size = Pt(9)
    return table


def rebuild_function_table(doc):
    function_table = doc.tables[0]
    # Keep only the header row.
    while len(function_table.rows) > 1:
        tr = function_table.rows[-1]._tr
        tr.getparent().remove(tr)
    for idx, item in enumerate(FUNCTIONS, 1):
        cells = function_table.add_row().cells
        values = [
            idx,
            item["name"],
            PRIMARY_MODULE,
            SECONDARY_MODULE,
            item["third"],
        ]
        for col, value in enumerate(values):
            cells[col].text = str(value)
    for row in function_table.rows:
        for cell in row.cells:
            for p in cell.paragraphs:
                for r in p.runs:
                    r.font.name = "宋体"
                    r._element.rPr.rFonts.set(
                        "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}eastAsia",
                        "宋体",
                    )
                    r.font.size = Pt(9)


def build_docx():
    template = find_template(".docx", "附件1")
    doc = Document(template)

    replace_first(doc, "XX项目需求说明书", DOC_TITLE)
    replace_first(doc, "XX部门", "软件交付部门")
    replace_first(doc, "XXXX年XX月", "2026年05月")
    replace_first(doc, "请在此处说明本项目需求的概要内容。", OVERALL_DESC)
    replace_first(doc, "请在此处详细描述本项目需求的建设目标。", BUILD_GOAL)
    replace_first(doc, "请在此处详细描述本项目需求的建设必要性。", BUILD_NECESSITY)
    replace_first(doc, "请在此处对系统整体情况说明。", f"{SYSTEM_NAME}为面向集客一线运维工作的支撑系统，本期围绕重要通知和紧急事务触达能力进行功能扩展。")
    replace_first(doc, "请在此处详细提供系统已实现功能情况、功能架构现状、已实现功能清单。", "系统已具备集客一线直通车相关运维支撑能力，本期在既有系统中新增通知公告模块，不改变原有业务主流程。")
    replace_first(doc, "功能清单示例：", "功能清单：")
    replace_first(doc, "请在此处详细描述系统目前存在的问题。", "当前重要通知主要依赖工作群沟通和人工盯控，缺少统一公告发布、审核留痕、定向触达、短信与 APP 双渠道提醒以及已读未读状态管理能力。")
    rebuild_function_table(doc)

    delete_between(doc, "功能架构图", "功能需求")
    func_heading = first_paragraph(doc, "功能需求")
    for text in [
        "本期在家宽运维支撑系统--集客一线直通车中新增通知公告模块，功能架构层级如下：",
        "一级模块：集客一线直通车",
        "二级模块：通知公告",
        "三级模块：公告管理、定向发布、公告审核、参数配置、消息接收",
        "模块类型说明：通知公告相关功能均为本期新增功能。",
    ]:
        insert_paragraph_before(func_heading, text)

    delete_between(doc, "功能需求", "非功能性需求（可选）")
    nonfunc = first_paragraph(doc, "非功能性需求（可选）")

    insert_paragraph_before(nonfunc, "4.1 集客一线直通车", "Heading 2", bold=True)
    insert_paragraph_before(nonfunc, "本期一级模块为集客一线直通车，主要承载面向集客一线运维人员的重要通知发布和消息触达能力。")
    insert_paragraph_before(nonfunc, "4.1.1 通知公告", "Heading 3", bold=True)
    insert_paragraph_before(nonfunc, "通知公告为本期新增二级模块，包含公告管理、定向发布、公告审核、参数配置和消息接收等能力。")

    headers = ["序号", "功能需求", "一级模块", "二级模块", "三级模块", "变更类型"]
    rows = [
        [idx, item["name"], PRIMARY_MODULE, SECONDARY_MODULE, item["third"], "新增"]
        for idx, item in enumerate(FUNCTIONS, 1)
    ]
    insert_paragraph_before(nonfunc, "功能需求清单如下：")
    insert_table_before(doc, nonfunc, headers, rows)
    insert_paragraph_before(nonfunc, "")

    for idx, item in enumerate(FUNCTIONS, 1):
        insert_paragraph_before(nonfunc, f"4.1.1.{idx} {item['name']}（新增）", "Heading 3", bold=True)
        insert_paragraph_before(nonfunc, "（1）关键时序图/业务逻辑图", bold=True)
        for step_idx, step in enumerate(item["steps"], 1):
            insert_paragraph_before(nonfunc, f"{step_idx}. {step}")
        insert_paragraph_before(nonfunc, "（2）功能描述", bold=True)
        insert_paragraph_before(nonfunc, item["desc"])
        insert_paragraph_before(nonfunc, "")

    replace_first(doc, "请填写非功能需求相关内容", "本期 XJ 需求未单独提出性能、可靠性、多重站点等专项非功能指标，后续如甲方补充明确指标，可在本章节追加说明。")

    out = OUTPUT_DIR / "附件1-集客一线直通车新增通知公告功能模块需求说明书V1.0.docx"
    doc.save(out)
    print(out)
    return out


def copy_cell_style(src, dst):
    if src.has_style:
        dst._style = copy(src._style)
    if src.number_format:
        dst.number_format = src.number_format
    if src.font:
        dst.font = copy(src.font)
    if src.fill:
        dst.fill = copy(src.fill)
    if src.border:
        dst.border = copy(src.border)
    if src.alignment:
        dst.alignment = copy(src.alignment)
    if src.protection:
        dst.protection = copy(src.protection)


def build_xlsx():
    template = find_template(".xlsx", "附件3")
    wb = load_workbook(template)
    ws = wb.worksheets[2]

    # Remove example merges and note merges below header.
    for merged in list(ws.merged_cells.ranges):
        if merged.min_row >= 6:
            ws.unmerge_cells(str(merged))

    # Clear example and note area.
    for row in ws.iter_rows(min_row=6, max_row=120, min_col=1, max_col=13):
        for cell in row:
            cell.value = None

    # Copy template style from example rows before filling.
    style_rows = {0: 6, 1: 7, 2: 8}
    current_row = 6
    total_rows = sum(len(item["rows"]) for item in MOVEMENTS)
    for r in range(6, 6 + total_rows + 3):
        source_row = style_rows.get((r - 6) % 3, 6)
        ws.row_dimensions[r].height = max(ws.row_dimensions[source_row].height or 60, 80)
        for c in range(1, 14):
            copy_cell_style(ws.cell(source_row, c), ws.cell(r, c))
            ws.cell(r, c).alignment = Alignment(wrap_text=True, vertical="center")

    for movement in MOVEMENTS:
        req = next(item for item in FUNCTIONS if item["id"] == movement["req"])
        start = current_row
        end = current_row + len(movement["rows"]) - 1

        base_values = [
            PROJECT_TITLE,
            PRIMARY_MODULE,
            SECONDARY_MODULE,
            req["third"],
            movement["user"],
            movement["trigger"],
            movement["process"],
        ]
        for col, value in enumerate(base_values, 1):
            ws.cell(start, col).value = value

        for row_offset, row_data in enumerate(movement["rows"]):
            desc, move_type, group, attrs = row_data
            row_no = start + row_offset
            ws.cell(row_no, 8).value = desc
            ws.cell(row_no, 9).value = move_type
            ws.cell(row_no, 10).value = group
            ws.cell(row_no, 11).value = attrs
            ws.cell(row_no, 12).value = "新增"
            ws.cell(row_no, 13).value = f'=IF(L{row_no}="新增",1,IF(L{row_no}="复用",1/3,IF(L{row_no}="利旧",0)))'

        if end > start:
            for col in range(1, 8):
                ws.merge_cells(start_row=start, start_column=col, end_row=end, end_column=col)
                ws.cell(start, col).alignment = Alignment(wrap_text=True, vertical="center")

        current_row = end + 1

    note_row = current_row + 1
    ws.merge_cells(start_row=note_row, start_column=1, end_row=note_row + 1, end_column=13)
    ws.cell(note_row, 1).value = "注：本表根据 XJ 需求说明书自动拆分并经人工复核后用于送审；A~M 列模板结构未新增列。"
    ws.cell(note_row, 1).alignment = Alignment(wrap_text=True, vertical="center")

    # Rebuild validation for reuse degree.
    ws.data_validations.dataValidation = [
        dv for dv in ws.data_validations.dataValidation
        if "L6" not in str(dv.sqref)
    ]
    dv = DataValidation(type="list", formula1='"新增,复用,利旧"', allow_blank=False)
    ws.add_data_validation(dv)
    dv.add(f"L6:L{current_row - 1}")

    ws.freeze_panes = "A6"
    out = OUTPUT_DIR / "附件3-集客一线直通车新增通知公告功能模块功能点拆分表V1.0.xlsx"
    wb.save(out)
    print(out)
    return out


if __name__ == "__main__":
    docx_path = build_docx()
    xlsx_path = build_xlsx()
    print(f"DOCX={docx_path}")
    print(f"XLSX={xlsx_path}")
