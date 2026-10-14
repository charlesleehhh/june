# 果价雷达 · 后端爬虫部署说明

单次运行的 Java 程序（无常驻进程），由 crontab 定时触发：抓价格 → 写数据库 → 生成 `prices.json` 到前端部署目录。目标环境 Java 8+，内存占用 <200MB。

```
crontab 触发 java -jar price-radar-crawler.jar
        │
        ├─ 抓取：Apple 官网页面解析 + 京东 p.3.cn 价格接口
        ├─ 入库：price_record 每日快照（另一台服务器上的 MySQL）
        ├─ 生成：data/prices.json（原子写，结构与前端完全一致）
        └─ 记录：fetch_log（成功/失败/条数）
```

## 一、数据库服务器（另一台）

```sql
CREATE DATABASE radar DEFAULT CHARSET utf8mb4;
CREATE USER 'radar'@'<应用服务器IP>' IDENTIFIED BY '强密码';
GRANT SELECT, INSERT, UPDATE, DELETE ON radar.* TO 'radar'@'<应用服务器IP>';
FLUSH PRIVILEGES;
```

然后在数据库服务器（或任何能连上它的机器）导入表结构和种子数据：

```bash
mysql -u root -p radar < database/schema.sql
```

种子数据与仓库当前 `data/prices.json` 内容一致，导入后前端数据无感切换。

## 二、应用服务器（跑网站这台）

```bash
mkdir -p /opt/data/charleslee/radar
# 上传 backend/target/price-radar-crawler.jar 和 config.properties 到该目录
cp backend/config.example.properties config.properties   # 本地先填好
vim config.properties                                     # 填数据库地址/账号、京东 skuId
```

首次试跑（观察日志确认两路抓取状态）：

```bash
cd /opt/data/charleslee/radar
java -jar price-radar-crawler.jar config.properties
# 输出 [crawler] apple -> N 条 / jd -> N 条 / [json] 已生成 即成功
```

配置 crontab（每天 9:30 / 13:30 / 19:30 各跑一次）：

```bash
crontab -e
30 9,13,19 * * * cd /opt/data/charleslee/radar && java -jar price-radar-crawler.jar config.properties >> radar.log 2>&1
```

## 三、京东 skuId 怎么填

京东商品页 URL 里的数字就是 skuId：`https://item.jd.com/100012043978.html` → `100012043978`。
在京东搜索「iPhone 18 Pro 256GB」等各配置，进入自营商品页，把 URL 里的数字填到 `config.properties` 的 `jd.sku.<variantCode>` 对应位置。没填的配置自动跳过。

## 四、各渠道现状（诚实说明）

| 渠道 | 自动抓取 | 说明 |
|------|---------|------|
| apple | ✅ 解析官网购买页 | 官网价稳定，主要用于校准 |
| jd | ✅ p.3.cn 公开价格接口 | 需配 skuId；若接口失效仅该渠道失败 |
| tmall / pdd / dewu / idle | ❌ 未实现 | 反爬强或无公开接口，数据库里保留已有历史价；可手工 SQL 补录当天数据，或后续逐渠道加 Fetcher |

补录示例（拼多多当天价格）：

```sql
INSERT INTO price_record (variant_id, channel_id, trade_date, price, promo, source)
SELECT v.id, c.id, CURDATE(), 8999, '百亿补贴', '手工录入'
FROM variant v, channel c
WHERE v.code='pro-256' AND c.code='pdd'
ON DUPLICATE KEY UPDATE price=8999;
```

## 五、prices.json 防覆盖机制

前端数据文件在 git 部署目录内，而部署脚本执行 `git reset --hard`，会覆盖服务器本地改动。已在 `.github/workflows/deploy.yml` 追加：

```bash
git update-index --skip-worktree data/prices.json
```

`skip-worktree` 使 `reset --hard` 跳过该文件——仓库里的 `data/prices.json` 仅作为首次部署的种子，之后以爬虫生成为准。**仓库里旧数据过期没关系，线上永远读服务器本地这份。**

## 六、构建（本地）

```bash
cd backend && mvn package -DskipTests
# 产物 target/price-radar-crawler.jar（fat jar，含全部依赖）
```
