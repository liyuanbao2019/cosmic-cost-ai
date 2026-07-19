# 项目目录结构

```text
cosmic-cost-ai
├── src/main/java/com/li/jc/cosmiccostai
│   ├── CosmicCostAiApplication.java
│   ├── common
│   │   └── package-info.java
│   ├── config
│   │   └── package-info.java
│   ├── controller
│   │   └── package-info.java
│   ├── dto
│   │   └── package-info.java
│   ├── model
│   │   └── package-info.java
│   └── service
│       └── package-info.java
├── src/main/resources
│   └── application.yml
├── templates
├── prompts
├── sample
├── output
├── storage
└── docs
```

## 分层说明

| 层 | 包名 | 说明 |
| --- | --- | --- |
| Controller | `controller` | REST API 入口，后续放上传、解析、导出接口 |
| Service | `service` | 应用服务，后续放文档解析、AI 提取、COSMIC 计算、文件导出 |
| DTO | `dto` | 请求和响应对象，不直接暴露内部文件结构 |
| Model | `model` | 文件型存储对应的领域对象 |
| Common | `common` | 统一响应、异常、常量 |
| Config | `config` | OpenAPI、AI、文件路径等配置 |

## 根目录说明

| 目录 | 说明 |
| --- | --- |
| `templates` | Excel、Word 模板 |
| `prompts` | AI 提示词模板 |
| `sample` | 测试样例文件 |
| `output` | 导出结果 |
| `storage` | 文件型数据存储 |
| `docs` | 项目设计文档 |
