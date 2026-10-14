# 果价雷达 · 后端与部署说明

站点仍是**纯静态**（`index.html` / `app.js` / `data/prices.json`），部署目录不变：`/opt/data/charleslee/github/june`。
后端只做一件事：**把 `data/prices.json` 写好**。前端一行都不用改。

```
苹果团报价页(appletuan.com) ──抓取──▶ price-radar-crawler.jar ──▶ MySQL（另一台服务器）
                                              │                        │
                                              └── 聚合生成 ─────────────┘
                                              ▼
                    /opt/data/charleslee/github/june/data/prices.json  ──▶ charleslee.fun
管理员 ──HTTPS──▶ nginx(/admin/ + Basic Auth) ──▶ 127.0.0.1:8099 管理员后台（录入价格）
```

## 一、数据来源（实测结论）

| 来源 | 结果 | 说明 |
|------|------|------|
| **苹果团 appletuan.com** | ✅ 可用 | 服务端渲染，价格直接写在 HTML 里；含国行/港行现货报价 + 真实成交订单流 |
| Apple 官网 | ❌ | 价格 JS 渲染，静态页面里 0 个价格 |
| 京东 | ❌ | `p.3.cn` 接口 TLS 握手被拒；商品页无价格；搜索接口要签名 |
| 拼多多 / 得物 / 天猫 | ❌ | 无公开接口，反爬强 |

因此**苹果团是唯一自动数据源，也是走势图的数据基础**：报价表给当天快照，成交记录按日回填历史。

## 二、五个命令

| 命令 | 作用 |
|------|------|
| `java -jar price-radar-crawler.jar config.properties` | 正常抓取一次（报价 + 成交 + 回填历史 + 生成 JSON） |
| `java -jar price-radar-crawler.jar --dry-run config.properties` | **只抓取并打印，不连数据库**（上线前先验网络与解析） |
| `java -jar price-radar-crawler.jar --serve config.properties` | 启动管理员后台（127.0.0.1:8099） |
| `java -jar price-radar-crawler.jar --ingest prices.csv config.properties` | 离线导入价格（批量补数据） |
| `java -jar price-radar-crawler.jar --diagnose config.properties` | 四层网络诊断（DNS→TCP→HTTP→解析） |

程序特性：单次运行即退出（无常驻）；数据库不可达 10 秒内失败退出；**0 条写入时绝不覆盖线上 JSON**；每次运行写运行日志表。

## 三、数据库初始化（你自己执行 SQL）

```bash
# 在目标库内依次执行（幂等，可重复跑）
mysql -h <host> -P <port> -u <user> -p <db> < database/schema.sql   # 建表 + 机型/渠道/大事记种子
mysql -h <host> -P <port> -u <user> -p <db> < database/seed.sql     # 真实价格与成交记录
```

`seed.sql` 内容（共 5 段）：苹果团渠道 → 由真实成交回填的历史价格 → 当日 16 条实时报价 → 15 笔真实成交 → 前期公开报道记录。
执行后校验语句会自动打印各表行数。

### 表命名约定：统一前缀 `radar_`

为便于与同库其它项目区分管理，所有表和**外键**统一带 `radar_` 前缀：

| 表 | 用途 |
|---|---|
| `radar_variant` | 机型配置 + 官网定价 |
| `radar_channel` | 销售渠道 |
| `radar_price_record` | 价格历史快照（核心表，含 `prev_price` 用于算涨跌） |
| `radar_tuan_order` | 苹果团真实成交记录 |
| `radar_market_event` | 行情大事记 |
| `radar_fetch_log` | 每次运行日志 |

外键名为 `fk_radar_*`——MySQL 要求外键名**全库唯一**，共用库时若不加前缀容易与其它项目撞名。

**要换前缀**只改三处：`backend/src/main/java/fun/charleslee/radar/Tables.java` 的 `PFX` 常量，
以及 `database/schema.sql`、`database/seed.sql`（一条命令批量替换）：

```bash
perl -pi -e 's/\bradar_/新前缀_/g' database/schema.sql database/seed.sql
```

> 之前若执行过无前缀的旧版建表语句，`schema.sql` 顶部有一行清理旧表的语句（默认注释掉，建议先备份再执行）。
> 若表已存在但缺列（如 `prev_price`），`CREATE TABLE IF NOT EXISTS` 不会补列，`schema.sql` 顶部也给了对应的 `ALTER TABLE`。

## 四、管理员后台（只有你能进）

价格录入以**后台**为准：表格里只填变了的价格 → 写库 → 立即重生成 `prices.json` → 访客刷新即见。

```bash
cd /opt/data/charleslee/radar
java -jar price-radar-crawler.jar --serve config.properties
```

**三层防护**：① 程序只绑定 `127.0.0.1`；② nginx Basic Auth；③ `config.properties` 里的 `admin.password`（可选第二层）。

```nginx
# printf "charleslee:%s\n" "$(openssl passwd -apr1 '你的强密码')" > /etc/nginx/.htpasswd-radar
location /admin/ {
    auth_basic           "guojia radar admin";
    auth_basic_user_file /etc/nginx/.htpasswd-radar;
    proxy_pass           http://127.0.0.1:8099/;
    proxy_set_header     Host $host;
}
```

常驻服务（`java` 路径用 `which java` 确认）：

```ini
# /etc/systemd/system/radar-admin.service
[Unit]
Description=Guojia Radar Admin
After=network.target
[Service]
WorkingDirectory=/opt/data/charleslee/radar
ExecStart=/usr/bin/java -Xmx128m -jar /opt/data/charleslee/radar/price-radar-crawler.jar --serve /opt/data/charleslee/radar/config.properties
Restart=always
User=root
[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now radar-admin
```

不想配 nginx 也可以用 SSH 隧道：`ssh -N -L 8099:127.0.0.1:8099 root@服务器`，再打开 `http://127.0.0.1:8099/`。

## 五、定时抓取

```bash
crontab -e
30 9,13,19 * * * cd /opt/data/charleslee/radar && java -Xmx128m -jar price-radar-crawler.jar config.properties >> radar.log 2>&1
```

抓取频率建议 ≤3 次/天（对端是人工维护的报价页，别给它压力）。

## 六、prices.json 防覆盖

部署脚本执行 `git reset --hard origin/main` 会覆盖服务器本地改动，已在 `.github/workflows/deploy.yml` 追加：

```bash
git update-index --skip-worktree data/prices.json || true
```

仓库里的 `data/prices.json` 仅作首次部署种子，之后以数据库生成为准。

## 七、离线导入（CSV）

```bash
java -jar price-radar-crawler.jar --ingest prices.csv config.properties
```

CSV 列：`variant,channel,date,price,promo,source`（date 留空 = 今天）

```
pro-256,tuan-hk,,9290,银色最低,手工录入
```

`variant`：`pro-256` `pro-512` `pro-1024` `pro-2048` `max-256` `max-512` `max-1024` `max-2048`
`channel`：`tuan-g` `tuan-hk` `apple` `tmall` `jd` `pdd` `dewu` `idle`

## 八、⚠️ 安全清单

1. **`config.example.properties` 绝不能写真实账号密码**——它会被提交到公开仓库。
   历史教训：曾把数据库密码写进模板并部署上线（`https://charleslee.fun/backend/config.example.properties` 可被下载）→
   **改数据库密码 + 清理 git 历史**（`git filter-repo --path backend/config.example.properties --invert-paths`）。
2. 服务器私钥同理：确认 `~/.ssh/authorized_keys` 旧公钥已删、密钥已换、历史已清。
3. 后台密码与数据库密码不要复用，nginx 走 HTTPS。
4. **记忆/笔记类文件同样会公开**——仓库根目录就是网站根目录，凡是 git 跟踪的文件都能被下载。
   历史教训：助手的项目记忆目录 `.workbuddy/memory/` 曾被跟踪，里面写过明文数据库密码，
   `https://charleslee.fun/.workbuddy/memory/2026-10-14.md` 可直接下载。
   已 `.gitignore` + `git rm -r --cached .workbuddy/`（本地保留）。**任何写进仓库的文件都不许含真实凭据。**

## 九、常见报错（排错）

程序启动会先打印一段 `[config] ...` 生效配置摘要（不含密码），排错先看这几行确认读的是哪个文件、哪套配置。

| 报错 | 原因 | 处理 |
|---|---|---|
| `[FATAL] For input string: ""` | 数字型配置项**留空**（如 `http.proxyPort=`、`admin.port=`）。旧版 jar 的行为 | 换新 jar 即可：现在**留空 = 用默认值** |
| `[FATAL] 配置项 http.proxyPort 必须是整数，当前写的是 "abc"` | 配置项值写错了 | 按提示改该项，或留空用默认值 |
| `[FATAL] 缺少必需配置项: db.url` | 只有 `db.url`/`db.user`/`db.password` 三项必填 | 补齐这三项 |
| `[FATAL] 配置文件不存在或无法读取: /xxx（当前目录 /yyy）` | 路径不对 / 不在程序所在目录 | 用绝对路径：`java -jar price-radar-crawler.jar /opt/data/charleslee/radar/config.properties` |
| `连接超时或握手被拒（常见原因：本机 IP 不在数据库白名单 / 端口不通）` | 数据库防火墙没放行 | 把应用服务器 IP 加进白名单 |
| `[crawler] tuan 失败: ...` | 对端改版或网络不通 | 跑 `--diagnose config.properties` 看是网络层还是解析层 |

配置文件规则：**除 `db.url` / `db.user` / `db.password` 必填外，其余项留空一律取程序内置默认值**，不会报错；
只有写了非法值才会点名具体配置项。

## 十、构建

```bash
cd backend && mvn package -DskipTests
# 产物 target/price-radar-crawler.jar（约 7MB fat jar，Java 8+ 运行）
```
