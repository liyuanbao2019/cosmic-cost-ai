# MVP 开发范围

## 第一阶段目标

将需求说明书自动转换为：

1. COSMIC 功能点分析结果
2. Excel 造价文件
3. Word 造价说明书

## 第一阶段功能

| 编号 | 功能 | 说明 |
| --- | --- | --- |
| 1 | 上传需求说明书 | MVP 当前仅支持 XJ*.docx |
| 2 | 文档内容解析 | 使用 Apache POI 提取 XJ*.docx 纯文本 |
| 3 | AI 结构化提取 | 已实现；只截取“需求细则”章节，通过 LangChain4j 调用 OpenAI 兼容接口 |
| 4 | COSMIC 分析 | 已实现；基于结构化功能按轻量规则输出 Entry、Read、Write、Exit、CFP |
| 5 | 审核修改 | 已实现；在导出前审核并修改 COSMIC 功能点结果 |
| 6 | Excel 导出 | 已实现；固定使用附件 3 模板，填充 Sheet2 |
| 7 | Word 导出 | 已实现；根据审核后的 COSMIC 结果生成说明书 |
| 8 | 一致性检查 | 已实现；校验 Excel、Word 与审核结果是否一一对应 |
| 5 | 统一中间结构 | 使用 `UC001` 形式组织用例 |
| 6 | Excel 导出 | 使用 Apache POI |
| 7 | Word 导出 | 使用 poi-tl |

## 暂不实现

- Vue 前端
- JDBC 数据库
- PostgreSQL
- Docker
- 用户登录和权限
- 工作流审批
- 多租户
- 复杂模板管理
