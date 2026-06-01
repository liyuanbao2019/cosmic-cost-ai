# cosmic-cost-ai

《基于 COSMIC 功能点的软件造价 AI 系统（MVP 轻量版）》

本版本按轻量化原则实现项目骨架：

- 单体 Spring Boot 2.7 服务
- Java 8 + Maven
- 本地文件型存储，不使用 JDBC、不连接数据库
- 不包含 Docker
- 暂不生成 Vue 前端
- 暂不生成上传、解析、AI、导出等业务代码

## 目录结构

```text
cosmic-cost-ai
|-- src
|   `-- main
|       |-- java
|       |   `-- com/example/cosmiccostai
|       |       |-- CosmicCostAiApplication.java
|       |       |-- common
|       |       |-- config
|       |       |-- controller
|       |       |-- dto
|       |       |-- model
|       |       `-- service
|       `-- resources
|           `-- application.yml
|-- templates
|-- prompts
|-- sample
|-- output
|-- storage
|-- docs
|-- pom.xml
`-- README.md
```

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
- 后续可通过 `base-url` 切换 DeepSeek

文件处理：

- Apache Tika
- PDFBox
- Apache POI
- poi-tl

存储：

- 本地 JSON 文件
- 原始文件存储在 `storage/projects/{projectId}/files`
- 导出文件存储在 `output`

## 启动方式

本项目需要：

- JDK 8
- Maven 3.6.1 或更高版本

项目已内置 `.mvn/settings.xml`，默认使用阿里云 Maven 镜像源。

```bash
mvn spring-boot:run
```

启动后访问：

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

## AI 配置

默认使用 DeepSeek OpenAI 兼容接口：

```bash
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=your-deepseek-key
OPENAI_MODEL=deepseek-v4-pro
```

也可以使用 `DEEPSEEK_API_KEY`：

```bash
DEEPSEEK_API_KEY=your-deepseek-key
```

## MVP 后续业务接口规划

当前已完成第二次迭代：上传接口。

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/files/upload` | POST | 已实现，上传 XJ*.docx 需求说明书 |
| `/api/projects/{id}/parse` | POST | 已实现，解析 XJ*.docx 并提取纯文本 |
| `/api/projects/{id}/extract` | POST | 已实现，只把“需求细则”章节发送给 AI 并结构化提取 |
| `/api/projects/{id}/cosmic` | POST | 已实现，基于 extracted-functions.json 计算 Entry/Read/Write/Exit/CFP |
| `/api/projects/{id}/review` | GET | 已实现，加载审核修改数据 |
| `/api/projects/{id}/review` | PUT | 已实现，保存审核修改后的 COSMIC 结果 |
| `/api/projects/{id}/export/excel` | POST | 导出 Excel |
| `/api/projects/{id}/export/word` | POST | 导出 Word |
| `/api/projects/{id}/exports/consistency` | GET | 已实现，检查 Excel/Word 与审核结果是否一一对应 |

上传示例：

```bash
curl -F "file=@sample/XJ-demo.docx" -F "projectName=演示项目" http://localhost:8080/api/files/upload
```

上传成功后会生成：

```text
storage/projects/{projectId}
|-- metadata.json
|-- document-text.txt
`-- files
    `-- original.docx
```

解析示例：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/parse
```

AI 结构化提取示例：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/extract
```

该接口会从 `document-text.txt` 中截取“需求细则”章节，写入：

```text
storage/projects/{projectId}/requirement-detail.txt
storage/projects/{projectId}/extracted-functions.json
```

COSMIC 分析示例：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/cosmic
```

该接口会生成：

```text
storage/projects/{projectId}/cosmic-results.json
```

## 审核修改页面

审核页面放在 COSMIC 分析之后、Excel/Word 导出之前：

```text
上传 -> 解析 -> AI提取 -> COSMIC分析 -> 人工审核修改 -> 导出Excel/Word -> 一致性检查
```

访问：

```text
http://localhost:8080/review.html
```

审核页会直接修改 `storage/projects/{projectId}/cosmic-results.json`，Excel 和 Word 都从该文件导出，因此二者使用同一份审核后的数据源。

一致性检查：

```bash
curl http://localhost:8080/api/projects/{projectId}/exports/consistency
```

## 设计文档

- [项目目录结构](docs/project-structure.md)
- [文件型数据存储设计](docs/file-storage-design.md)
- [MVP 开发范围](docs/mvp-scope.md)
