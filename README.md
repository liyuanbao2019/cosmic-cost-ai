# cosmic-cost-ai

基于 COSMIC 功能点的软件造价 AI 辅助系统，当前版本为轻量化 MVP。系统支持上传需求说明书，自动解析需求文本，调用 OpenAI 兼容模型提取功能点，生成功能点拆分表，并导出 Excel 与 Word 送审材料。

## 当前能力

- 上传 `docx` 需求说明书并生成项目档案。
- 解析需求文档文本，截取需求细则内容。
- 调用 OpenAI 兼容接口进行 AI 功能点提取。
- 基于 COSMIC 方法计算 Entry、Read、Write、Exit 与 CFP。
- 在页面中人工审核和修改功能点拆分表。
- 审核页支持新增、复制、删除、勾选多行和批量修改拆分行。
- 导出 Excel 功能点拆分表。
- 导出 Word 需求说明书。
- 导出送审材料时自动执行 Excel 与 Word 一致性检查。
- 支持下载导出文件，中文文件名按 UTF-8 输出。
- 使用本地文件存储，不依赖数据库。

## 最新导出规则

Excel 导出时会写入固定模板的第 2 个工作表：`2、功能点拆分表`。

导出格式规则：

- 数据区从第 6 行开始写入。
- A:M 数据区会统一设置表格边框。
- A:D 四列会按层级合并相邻相同内容：
  - A 列：客户需求，相邻内容相同则合并。
  - B 列：一级模块，需要客户需求相同且一级模块相同才合并。
  - C 列：二级模块，需要客户需求、一级模块、二级模块都相同才合并。
  - D 列：三级模块，需要客户需求、一级模块、二级模块、三级模块都相同才合并。
- 如果旧导出文件正被 WPS/Excel/Word 占用，系统会自动生成带时间后缀的新文件，避免导出失败。

一致性检查规则：

- 以人工审核后的 `function-split-rows.json` / `cosmic-results.json` 为源数据。
- Excel 按功能过程读取并累加 E/R/W/X/CFP。
- Word 按功能过程读取并累加 E/R/W/X/CFP。
- 当同一个功能过程在 Word 中因为模块层级不同被分成多个小节时，会按同名功能过程累加，不再覆盖。

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
|   `-- 附件3-XX项目功能点拆分表V1.0.xlsx
|-- prompts
|-- sample
|-- output
|-- storage
|-- docs
|-- pom.xml
`-- README.md
```

目录说明：

| 目录 | 说明 |
| --- | --- |
| `src/main/java` | Spring Boot 后端代码 |
| `src/main/resources/static` | 前端页面 |
| `templates` | Excel、Word 固定模板 |
| `sample` | 测试样例文件 |
| `storage` | 项目元数据、上传原始文件、AI 结果、审核结果 |
| `output` | 导出的 Excel 与 Word 文件 |
| `docs` | 设计与部署文档 |
| `prompts` | AI 提示词 |

## 技术栈

后端：

- Java 8
- Spring Boot 2.7.x
- Maven
- Spring Web
- Spring Validation
- Spring Actuator
- Springdoc OpenAPI / Swagger

AI：

- LangChain4j
- OpenAI 兼容接口
- 默认按 DeepSeek OpenAI 兼容接口配置

文档处理：

- Apache POI
- Apache Tika
- PDFBox
- poi-tl

存储：

- 本地 JSON 文件
- 不使用 JDBC、JPA、MyBatis 或外部数据库

## 本地启动

环境要求：

- JDK 8
- Maven 3.6.1 或更高版本

项目内置 `.mvn/settings.xml`，默认使用阿里云 Maven 镜像源。

启动方式：

```bash
mvn spring-boot:run
```

或先打包再启动：

```bash
mvn clean package -DskipTests
java -jar target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar
```

启动后访问：

- 首页：`http://localhost:8080/`
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
```

推荐使用环境变量：

```bash
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=your-api-key
OPENAI_MODEL=deepseek-v4-pro
```

也可以只配置：

```bash
DEEPSEEK_API_KEY=your-api-key
```

## 模板配置

默认模板路径：

```yaml
cosmic:
  template:
    excel-path: ${COSMIC_EXCEL_TEMPLATE:templates/附件3-XX项目功能点拆分表V1.0.xlsx}
    word-path: ${COSMIC_WORD_TEMPLATE:templates/附件1-XX项目需求说明书V1.0.docx}
```

服务器部署时需要确保 `templates` 目录随项目一起上传。

## 主要流程

```text
上传需求文档
  -> 解析文档
  -> AI 提取功能点
  -> COSMIC 分析
  -> 人工审核修改功能点拆分表
  -> 导出 Excel
  -> 导出 Word
  -> 一致性检查
  -> 下载送审材料
```

## 审核页操作

功能点拆分表支持在导出前进行人工修订：

- 直接修改单元格内容。
- 新增一行，用于补充 AI 漏拆的功能或数据移动。
- 复制单行或复制选中行，用于快速补充相似拆分项。
- 删除单行或删除选中行，至少保留一行数据。
- 勾选多行后批量修改一级模块、二级模块、三级模块、功能过程、子过程描述、类型、数据组、数据属性和复用度。
- 修改复用度后自动刷新 CFP。

## 页面入口

```text
http://localhost:8080/
```

当前首页包含上传、处理、人工审核、导出送审材料等主要操作。

## API 列表

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/files/upload` | POST | 上传需求说明书 |
| `/api/projects/{id}/parse` | POST | 解析需求说明书 |
| `/api/projects/{id}/extract` | POST | AI 提取功能点 |
| `/api/projects/{id}/cosmic` | POST | COSMIC 分析 |
| `/api/projects/{id}/review` | GET | 加载人工审核数据 |
| `/api/projects/{id}/review` | PUT | 保存人工审核后的功能点拆分表 |
| `/api/projects/{id}/export/excel` | POST | 导出 Excel |
| `/api/projects/{id}/export/word` | POST | 导出 Word |
| `/api/projects/{id}/exports/consistency` | GET | 检查 Excel、Word 与审核结果是否一致 |
| `/api/projects/{id}/exports/{fileType}/info` | GET | 获取导出文件信息 |
| `/api/projects/{id}/exports/{fileType}/download` | GET | 下载导出文件 |
| `/api/projects/{id}/exports/{fileType}/open-directory` | POST | Windows 环境下打开导出目录 |

`fileType` 支持：

- `excel`
- `word`

## API 示例

上传：

```bash
curl -F "file=@sample/XJ-sample.docx" \
  -F "projectName=演示项目" \
  http://localhost:8080/api/files/upload
```

解析：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/parse
```

AI 提取：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/extract
```

COSMIC 分析：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/cosmic
```

导出 Excel：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/export/excel
```

导出 Word：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/export/word
```

一致性检查：

```bash
curl http://localhost:8080/api/projects/{projectId}/exports/consistency
```

下载导出文件：

```bash
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/excel/download
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/word/download
```

## 存储文件

上传成功后生成：

```text
storage/projects/{projectId}
|-- metadata.json
|-- document-text.txt
|-- requirement-detail.txt
|-- extracted-functions.json
|-- cosmic-results.json
|-- function-split-rows.json
`-- files
    `-- original.docx
```

字段说明：

- `metadata.json`：项目元数据、状态、文件路径。
- `document-text.txt`：文档解析后的纯文本。
- `requirement-detail.txt`：截取后的需求细则。
- `extracted-functions.json`：AI 提取的功能点。
- `cosmic-results.json`：COSMIC 统计结果。
- `function-split-rows.json`：人工审核后的功能点拆分表行数据。

导出文件生成在：

```text
output/{projectId}
|-- 附件3-...功能点拆分表V1.0.xlsx
`-- 附件1-....docx
```

如果同名文件被占用，会生成：

```text
附件3-...功能点拆分表V1.0-yyyymmddHHMMSS.xlsx
附件1-...-yyyymmddHHMMSS.docx
```

## 部署

服务器部署请参考：

- [服务器部署文档](docs/server-deployment.md)

部署时至少需要上传：

- `target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar`
- `templates/`
- `src/main/resources/application.yml` 中对应的配置

不要覆盖服务器上的 `storage` 和 `output`，除非确认要清理历史数据。

## 相关文档

- [服务器部署文档](docs/server-deployment.md)
- [项目目录结构](docs/project-structure.md)
- [文件型数据存储设计](docs/file-storage-design.md)
- [MVP 开发范围](docs/mvp-scope.md)
- [COSMIC 附件自动生成系统架构设计](docs/COSMIC附件自动生成系统架构设计.md)
