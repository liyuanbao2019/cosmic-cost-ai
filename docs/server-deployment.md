# cosmic-cost-ai 服务器部署文档

本文档适用于把 `cosmic-cost-ai` 部署到 Linux 服务器，运行方式为 Spring Boot Jar + 本地文件存储。系统当前不依赖数据库，业务数据写入 `storage`，导出文件写入 `output`，模板文件放在 `templates`。

## 1. 服务器准备

推荐环境：

- Linux：CentOS 7/8、Rocky Linux、Ubuntu 20.04+ 均可
- JDK：Java 8
- Maven：3.6.1 或更高版本
- 内存：建议 2 GB 以上
- 磁盘：按上传文档、导出文件规模预留，建议先预留 20 GB 以上
- 端口：默认 `8080`

安装示例：

```bash
# Ubuntu / Debian
sudo apt update
sudo apt install -y openjdk-8-jdk maven nginx

# CentOS / Rocky Linux
sudo yum install -y java-1.8.0-openjdk-devel maven nginx
```

验证：

```bash
java -version
mvn -version
```

## 2. 上传项目

建议部署目录：

```bash
/opt/cosmic-cost-ai
```

把整个 `cosmic-cost-ai` 目录上传到服务器，确保以下目录和文件都在项目内：

```text
/opt/cosmic-cost-ai
├── src
├── templates
│   ├── 附件1-XX项目需求说明书V1.0.docx
│   └── 附件3-XX项目功能点拆分表V1.0.xlsx
├── sample
├── prompts
├── docs
├── pom.xml
└── README.md
```

如果使用 `scp`：

```bash
scp -r cosmic-cost-ai user@server:/opt/
```

## 3. 配置环境变量

系统默认使用 DeepSeek OpenAI 兼容接口。至少需要配置 API Key：

```bash
export OPENAI_API_KEY="your-api-key"
export OPENAI_BASE_URL="https://api.deepseek.com/v1"
export OPENAI_MODEL="deepseek-v4-pro"
```

可选配置：

```bash
export OPENAI_TIMEOUT_SECONDS=180
export OPENAI_MAX_RETRIES=2
export COSMIC_EXCEL_TEMPLATE="templates/附件3-XX项目功能点拆分表V1.0.xlsx"
export COSMIC_WORD_TEMPLATE="templates/附件1-XX项目需求说明书V1.0.docx"
export COSMIC_MULTIPART_LOCATION="./storage/tmp"
```

说明：

- `storage`：保存项目元数据、上传原始文件、AI 结果和审核结果。
- `output`：保存导出的 Excel、Word 文件。
- `templates`：保存固定模板，服务器部署时必须随项目一起上传。

## 4. 构建 Jar

进入项目目录：

```bash
cd /opt/cosmic-cost-ai
mvn clean package -DskipTests
```

构建成功后 Jar 位于：

```text
target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar
```

## 5. 首次启动验证

```bash
cd /opt/cosmic-cost-ai
OPENAI_API_KEY="your-api-key" \
java -jar target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar
```

访问：

```text
http://服务器IP:8080/
http://服务器IP:8080/actuator/health
http://服务器IP:8080/swagger-ui.html
```

健康检查返回 `UP` 即表示服务已启动。

## 6. systemd 后台运行

创建运行用户：

```bash
sudo useradd -r -s /sbin/nologin cosmic || true
sudo chown -R cosmic:cosmic /opt/cosmic-cost-ai
```

创建环境变量文件：

```bash
sudo mkdir -p /etc/cosmic-cost-ai
sudo vi /etc/cosmic-cost-ai/cosmic-cost-ai.env
```

内容示例：

```bash
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=your-api-key
OPENAI_MODEL=deepseek-v4-pro
OPENAI_TIMEOUT_SECONDS=180
OPENAI_MAX_RETRIES=2
COSMIC_EXCEL_TEMPLATE=templates/附件3-XX项目功能点拆分表V1.0.xlsx
COSMIC_WORD_TEMPLATE=templates/附件1-XX项目需求说明书V1.0.docx
COSMIC_MULTIPART_LOCATION=./storage/tmp
```

创建 service：

```bash
sudo vi /etc/systemd/system/cosmic-cost-ai.service
```

内容：

```ini
[Unit]
Description=cosmic-cost-ai service
After=network.target

[Service]
Type=simple
User=cosmic
Group=cosmic
WorkingDirectory=/opt/cosmic-cost-ai
EnvironmentFile=/etc/cosmic-cost-ai/cosmic-cost-ai.env
ExecStart=/usr/bin/java -Xms512m -Xmx1536m -jar /opt/cosmic-cost-ai/target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable cosmic-cost-ai
sudo systemctl start cosmic-cost-ai
sudo systemctl status cosmic-cost-ai
```

查看日志：

```bash
journalctl -u cosmic-cost-ai -f
```

## 7. 可选：Nginx 反向代理

如果希望通过域名访问，可配置 Nginx：

```nginx
server {
    listen 80;
    server_name your-domain.com;

    client_max_body_size 60m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

重载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

## 8. 更新发布

```bash
cd /opt/cosmic-cost-ai
mvn clean package -DskipTests
sudo systemctl restart cosmic-cost-ai
```

如果是从本地重新上传包，至少需要上传：

```text
target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar
templates/
src/main/resources/application.yml
```

不要覆盖服务器上的 `storage` 和 `output`，除非确认要清理历史数据。

## 9. 备份与恢复

需要定期备份：

```text
/opt/cosmic-cost-ai/storage
/opt/cosmic-cost-ai/output
/opt/cosmic-cost-ai/templates
/etc/cosmic-cost-ai/cosmic-cost-ai.env
```

备份示例：

```bash
tar -czf cosmic-cost-ai-backup-$(date +%F).tar.gz \
  /opt/cosmic-cost-ai/storage \
  /opt/cosmic-cost-ai/output \
  /opt/cosmic-cost-ai/templates \
  /etc/cosmic-cost-ai/cosmic-cost-ai.env
```

## 10. 常见问题

模板找不到：

- 确认 `templates/附件1-XX项目需求说明书V1.0.docx` 存在。
- 确认 `templates/附件3-XX项目功能点拆分表V1.0.xlsx` 存在。
- 如果使用自定义路径，确认 `COSMIC_EXCEL_TEMPLATE` 和 `COSMIC_WORD_TEMPLATE` 指向正确文件。

上传失败：

- 检查 `storage/tmp` 是否可写。
- 检查 Nginx `client_max_body_size` 是否不小于 60 MB。

AI 提取失败：

- 检查 `OPENAI_API_KEY` 是否正确。
- 检查服务器是否能访问 `OPENAI_BASE_URL`。
- 查看 `journalctl -u cosmic-cost-ai -f` 中的错误信息。

端口冲突：

- 修改 `src/main/resources/application.yml` 中的 `server.port`，或启动时增加参数：

```bash
java -jar target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar --server.port=8081
```
