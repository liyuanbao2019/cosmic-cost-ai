# 文件型数据存储设计

轻量版 MVP 不使用 PostgreSQL、JDBC、JPA 或 MyBatis。系统数据以 JSON 文件形式落在 `storage` 目录，适合单机部署、快速验证和小团队内部使用。

## 存储根目录

```text
storage
└── projects
    └── {projectId}
        ├── metadata.json
        ├── document-text.txt
        ├── requirement-detail.txt
        ├── extracted-functions.json
        ├── cosmic-results.json
        ├── use-cases.json
        └── files
            └── original.docx
```

## 项目元数据

文件：`metadata.json`

```json
{
  "id": "P202605310001",
  "name": "集客一线直通车新增通知公告功能模块",
  "sourceFileName": "XJ-091-250905-00002.docx",
  "status": "UPLOADED",
  "createdAt": "2026-05-31T15:00:00",
  "updatedAt": "2026-05-31T15:00:00"
}
```

## 解析文本

文件：`document-text.txt`

```text
从 docx 或 pdf 中提取出的纯文本内容。
```

当前 MVP 仅解析 `XJ*.docx` 需求说明书。

## AI 结构化提取结果

文件：`extracted-functions.json`

```json
[
  {
    "module": "用户管理",
    "function": "用户新增",
    "description": "新增系统用户"
  }
]
```

AI 输入文件：`requirement-detail.txt`

该文件只包含 XJ 需求说明书中的“需求细则”章节。背景、目标、归属系统等固定信息不会送入 AI。

## COSMIC 分析结果

文件：`cosmic-results.json`

```json
[
  {
    "module": "用户管理",
    "function": "用户新增",
    "entry": 1,
    "read": 0,
    "write": 1,
    "exit": 1,
    "cfp": 3
  }
]
```

## 统一中间结构

文件：`use-cases.json`

```json
[
  {
    "id": "UC001",
    "module": "用户管理",
    "function": "用户新增",
    "description": "新增系统用户",
    "entry": 1,
    "read": 0,
    "write": 1,
    "exit": 1,
    "cfp": 3
  }
]
```

## 导出结果

```text
output
└── {projectId}
    ├── cosmic-cost.xlsx
    └── cosmic-report.docx
```

## 状态流转

| 状态 | 说明 |
| --- | --- |
| `UPLOADED` | 已上传原始需求说明书 |
| `PARSED` | 已提取纯文本 |
| `EXTRACTED` | 已完成 AI 结构化提取 |
| `ANALYZED` | 已完成 COSMIC 分析 |
| `EXPORTED` | 已导出 Excel 和 Word |

## 轻量版限制

- 不支持多人并发编辑同一项目。
- 不支持复杂查询，列表页需要扫描 `storage/projects`。
- 不适合海量项目归档。
- 后续如需要企业级能力，可平滑迁移到 PostgreSQL。
