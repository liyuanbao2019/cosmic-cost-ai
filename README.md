# cosmic-cost-ai

基于 COSMIC 功能点方法的软件造价 AI 辅助系统。系统支持上传需求说明书，解析需求文本，通过 OpenAI 兼容模型提取功能过程，生成 COSMIC 功能点拆分表，并导出送审所需的 Excel / Word 材料。

## 当前能力

- 支持上传 `docx` / `pdf` 需求文档，并为每次上传生成独立项目档案。
- 自动解析文档正文，截取“需求内容 / 需求细则 / 业务功能 / 功能描述”等章节作为 AI 拆分输入。
- 支持查看 AI 调用提示词、复制提示词、编辑提示词并重新发起 AI 拆分。
- 基于 AI 结果生成 COSMIC 功能过程，并计算 Entry / Read / Write / Exit 与 CFP。
- 提供人工审核页，可修改、新增、复制、删除、勾选多行、批量修改功能点拆分行。
- 审核页支持搜索过滤、按一级模块 / 类型 / 复用度筛选，以及紧凑 / 宽松视图切换。
- 拆分行支持“功能描述”和“时序图”字段，Word 导出会按时序图生成业务逻辑图。
- 一键导出送审材料：附件3功能点拆分表、附件2业务需求信息汇总表、附件1需求说明书。
- 支持下载导出文件；Windows 桌面环境下可直接打开导出目录。
- 使用本地文件存储项目数据，不依赖数据库。

## 最新导出规则

### 附件3：功能点拆分表

- 使用 `templates` 目录下以“附件3”开头的固定 Excel 模板。
- 写入模板的第 2 个工作表：`2、功能点拆分表`。
- 数据从第 6 行开始写入。
- 写入列范围扩展到 `A:O`：
  - `A:G`：项目 / 模块 / 用户 / 触发事件 / 功能过程等层级信息。
  - `H:M`：子过程描述、E/R/W/X、数据组、数据属性、复用度、CFP。
  - `N:O`：功能描述、时序图。
- `A:G` 会按父级层级合并相邻相同内容。
- `L` 列“复用度”提供下拉选项：`新增`、`复用`、`利旧`。
- `M` 列 CFP 由复用度自动计算：新增 = `1`，复用 = `1/3`，利旧 = `0`。
- 如果目标文件已存在，会自动生成带 `yyyyMMddHHmmss` 后缀的新文件，避免覆盖正在被 WPS / Excel / Word 占用的文件。

### 附件2：业务需求信息汇总表

- 使用 `templates` 目录下以“附件2”开头的固定 Excel 模板。
- 按一级 / 二级 / 三级模块聚合审核后的拆分行。
- 自动写入功能需求列表、功能点数汇总和总 CFP。
- 复用度为“新增”的需求标记为新增，其余标记为优化。

### 附件1：需求说明书

- 使用 `templates` 目录下以“附件1”开头的 Word 模板。
- 自动替换封面项目名称和年月。
- 读取已导出的附件3，确保 Word 内容来自最终审核后的拆分表。
- 按一级模块、二级模块、功能过程生成“功能需求”章节。
- 根据“时序图”字段生成 PlantUML PNG 并插入 Word。
- 每个功能过程会写入业务逻辑步骤和功能描述。
- Word 导出前必须先导出 Excel。

## 目录结构

```text
cosmic-cost-ai
|-- src
|   `-- main
|       |-- java
|       |   `-- com/example/cosmiccostai
|       |       |-- common
|       |       |-- controller
|       |       |-- dto
|       |       |-- model
|       |       `-- service
|       `-- resources
|           |-- application.yml
|           `-- static
|-- templates
|   |-- 附件1-XX项目需求说明书V1.0.docx
|   |-- 附件2-业务需求信息汇总表V1.0.xlsx
|   `-- 附件3-XX项目功能点拆分表V1.0.xlsx
|-- prompts
|-- sample
|-- storage
|-- output
|-- docs
|-- pom.xml
`-- README.md
```

| 目录 | 说明 |
| --- | --- |
| `src/main/java` | Spring Boot 后端代码 |
| `src/main/resources/static` | 前端单页工作台 |
| `templates` | 附件1 / 附件2 / 附件3 固定模板 |
| `storage` | 项目元数据、上传文件、解析文本、AI 结果、审核结果 |
| `output` | 导出的 Excel / Word 送审材料 |
| `docs` | 架构、部署和存储设计文档 |
| `prompts` | AI 提示词相关材料 |

## 技术栈

- 后端：Java 8、Spring Boot 2.7.18、Maven、Spring Web、Spring Validation、Spring Actuator。
- API 文档：Springdoc OpenAPI / Swagger UI。
- AI：LangChain4j、OpenAI 兼容接口，默认按 DeepSeek OpenAI 兼容接口配置。
- 文档处理：Apache POI、Apache Tika、PDFBox、poi-tl、PlantUML。
- 存储：本地 JSON / 文本 / 原始文件，不使用 JDBC、JPA、MyBatis 或外部数据库。

## 本地启动

环境要求：

- JDK 8
- Maven 3.6.1 或更高版本

启动：

```bash
mvn spring-boot:run
```

或先打包再启动：

```bash
mvn clean package -DskipTests
java -jar target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar
```

启动后访问：

- 工作台：`http://localhost:8080/`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- Health：`http://localhost:8080/actuator/health`

## AI 配置

默认配置位于 `src/main/resources/application.yml`：

```yaml
cosmic:
  ai:
    provider: deepseek
    base-url: ${OPENAI_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${OPENAI_API_KEY:${DEEPSEEK_API_KEY:}}
    model: ${OPENAI_MODEL:deepseek-v4-pro}
    timeout-seconds: ${OPENAI_TIMEOUT_SECONDS:180}
    max-retries: ${OPENAI_MAX_RETRIES:2}
```

推荐使用环境变量：

```bash
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=your-api-key
OPENAI_MODEL=deepseek-v4-pro
OPENAI_TIMEOUT_SECONDS=180
OPENAI_MAX_RETRIES=2
```

也可以只配置：

```bash
DEEPSEEK_API_KEY=your-api-key
```

## 模板与存储配置

默认配置：

```yaml
cosmic:
  storage:
    base-dir: storage
  output:
    base-dir: output
  template:
    excel-path: ${COSMIC_EXCEL_TEMPLATE:templates/附件3-XX项目功能点拆分表V1.0.xlsx}
    word-path: ${COSMIC_WORD_TEMPLATE:templates/附件1-XX项目需求说明书V1.0.docx}
```

说明：

- 附件3优先使用 `COSMIC_EXCEL_TEMPLATE` 指定的模板；附件2会自动在 `templates` 目录查找以“附件2”开头的 `.xlsx`。
- 附件1优先使用 `COSMIC_WORD_TEMPLATE` 指定的模板。
- 服务器部署时必须随应用一起上传 `templates` 目录。
- 不要覆盖服务器上的 `storage` 和 `output`，除非确认要清理历史项目和导出材料。

## 主要流程

```text
上传需求文档
  -> 解析文档并预览需求细则
  -> 查看 / 编辑 AI 提示词
  -> AI 拆分功能过程
  -> COSMIC 分析
  -> 人工审核功能点拆分表
  -> 导出附件3与附件2
  -> 导出附件1
  -> 下载或打开送审材料目录
```

## 页面操作

入口：

```text
http://localhost:8080/
```

工作台能力：

- 上传需求文档并选择项目名称。
- 点击“开始拆分”串行完成上传、解析、AI 拆分、COSMIC 分析和审核数据加载。
- 点击流程中的“解析”可展开 / 收起解析预览。
- 点击流程中的“AI 拆分”可查看提示词、复制提示词、编辑提示词并重新发起拆分。
- 在审核表中可直接编辑单元格；长文本字段会打开编辑弹窗。
- 支持新增行、复制单行、复制选中、删除选中和批量修改。
- 修改复用度后自动刷新 CFP。
- 点击“导出送审材料”会依次生成附件3、附件2和附件1。

## API 列表

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/files/upload` | POST | 上传需求说明书，支持 `docx` / `pdf` |
| `/api/projects/{projectId}/parse` | POST | 解析需求说明书 |
| `/api/projects/{projectId}/extract` | POST | 使用默认提示词执行 AI 功能点提取 |
| `/api/projects/{projectId}/ai-prompt` | GET | 获取当前项目 AI 提示词和模型名 |
| `/api/projects/{projectId}/ai-prompt/extract` | POST | 使用编辑后的提示词重新执行 AI 提取 |
| `/api/projects/{projectId}/cosmic` | POST | 执行 COSMIC 分析 |
| `/api/projects/{projectId}/review` | GET | 加载人工审核数据 |
| `/api/projects/{projectId}/review` | PUT | 保存人工审核后的拆分表 |
| `/api/projects/{projectId}/export/excel` | POST | 导出附件3功能点拆分表，并同步导出附件2业务需求信息汇总表 |
| `/api/projects/{projectId}/export/word` | POST | 导出附件1需求说明书 |
| `/api/projects/{projectId}/exports/{fileType}/info` | GET | 获取导出文件路径、下载链接和打开目录能力 |
| `/api/projects/{projectId}/exports/{fileType}/download` | GET | 下载导出文件 |
| `/api/projects/{projectId}/exports/{fileType}/open-directory` | POST | Windows 环境下打开导出目录 |

`fileType` 支持：

- `excel`：附件3功能点拆分表
- `business`：附件2业务需求信息汇总表
- `word`：附件1需求说明书

## API 示例

上传：

```bash
curl -F "file=@sample/example.docx" \
  -F "projectName=演示项目" \
  http://localhost:8080/api/files/upload
```

解析：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/parse
```

查看 AI 提示词：

```bash
curl http://localhost:8080/api/projects/{projectId}/ai-prompt
```

使用编辑后的提示词重新拆分：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/ai-prompt/extract \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"这里放编辑后的完整提示词\"}"
```

AI 拆分与 COSMIC 分析：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/extract
curl -X POST http://localhost:8080/api/projects/{projectId}/cosmic
```

导出送审材料：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/export/excel
curl -X POST http://localhost:8080/api/projects/{projectId}/export/word
```

下载导出文件：

```bash
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/excel/download
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/business/download
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/word/download
```

## 存储文件

上传并处理后会在项目目录下生成：

```text
storage/projects/{projectId}
|-- metadata.json
|-- document-text.txt
|-- requirement-detail.txt
|-- ai-prompt.txt
|-- ai-prompt-user-edited.flag
|-- extracted-functions.json
|-- cosmic-results.json
|-- function-split-rows.json
`-- files
    `-- original.{docx|pdf}
```

字段说明：

- `metadata.json`：项目元数据、状态、源文件、存储路径和导出路径。
- `document-text.txt`：文档解析后的纯文本。
- `requirement-detail.txt`：截取后的需求细则文本。
- `ai-prompt.txt`：当前项目 AI 拆分提示词。
- `ai-prompt-user-edited.flag`：标记提示词是否由用户编辑过。
- `extracted-functions.json`：AI 提取的功能过程。
- `cosmic-results.json`：COSMIC 统计结果。
- `function-split-rows.json`：人工审核后的功能点拆分表行数据。

导出文件生成在：

```text
output/{projectId}
|-- 附件3-...的需求功能点拆分表V1.0.xlsx
|-- 附件2-...的业务需求信息汇总表V1.0.xlsx
`-- 附件1-....docx
```

如果同名文件已存在，会生成：

```text
附件3-...的需求功能点拆分表V1.0-yyyyMMddHHmmss.xlsx
附件2-...的业务需求信息汇总表V1.0-yyyyMMddHHmmss.xlsx
附件1-...-yyyyMMddHHmmss.docx
```

## 部署

服务器部署请参考：

- [服务器部署文档](docs/server-deployment.md)

部署时至少需要上传：

- `target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar`
- `templates/`
- 必要的环境变量或 `application.yml` 配置

## 相关文档

- [服务器部署文档](docs/server-deployment.md)
- [项目目录结构](docs/project-structure.md)
- [文件型数据存储设计](docs/file-storage-design.md)
- [MVP 开发范围](docs/mvp-scope.md)
- [COSMIC 附件自动生成系统架构设计](docs/COSMIC附件自动生成系统架构设计.md)
