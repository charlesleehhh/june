# june 项目 · 长期备忘

## 项目性质
- 仓库 `charlesleehhh/june`（GitHub，**public**），本地路径 `/Users/charleslee/Documents/TRAE/june`。
- **仓库根目录 = charleslee.fun 的站点根目录**：服务器 `/opt/data/charleslee/github/june` 执行
  `git fetch && git reset --hard origin/main`，所以 **仓库里有什么，线上就能访问到什么**。
  在根目录放任何文件都等于公开发布 —— 敏感文件必须进 `.gitignore`。
- 部署链路：`.github/workflows/deploy.yml`，push 到 main → appleboy/ssh-action 登服务器拉代码。
  前端改动**只需 git 提交推送**，不需要在本地构建。

## 硬性约定
- **密钥一律不入库**：`github-deploy-key` / `github-deploy-key.pub` 曾泄露并已 `git rm --cached`，
  `.gitignore` 已覆盖。改动密钥后要同步更新 GitHub Secret `SERVER_PRIVATE_KEY`。
- **真实配置只放 `backend/config.properties`（已 ignore）**：`config.example.properties` 会被提交到公开仓库，
  2026-10-14 曾误把数据库账号密码写进去并部署上线，**永不重犯**。
  `.gitignore` 现已覆盖：`backend/config.properties`、`backend/target/`、`backend/dependency-reduced-pom.xml`、`.idea/`。
- 站点前端仍是**纯静态**（`data/prices.json`，改文件即上线）；2026-10-14 起后端另有一套
  Java 程序负责生成该 JSON（见下）。

## 后端：price-radar-crawler（2026-10-14 起）
- 位置 `backend/`（Maven，Java 8 兼容，shade fat jar ~6.6MB，产物 `target/price-radar-crawler.jar`）。
- 部署：程序在服务器 `/opt/data/charleslee/radar`，网站目录仍是 `/opt/data/charleslee/github/june`。
- 数据库在**另一台服务器**（MySQL），只对应用服务器 IP 放行，本机公网直连被拒（已实测）。
- 命令：默认抓取一次（cron）/ `--dry-run`（只抓不打库，验证网络解析）/ `--serve` 管理员后台
  (127.0.0.1:8099，nginx Basic Auth) / `--ingest prices.csv|json` / `--diagnose`（四层网络诊断）。
  **已按要求删除**：邮箱采集（MailFetcher）、一键建库（--init-db，改为直接给 SQL）。
- 表结构 `database/schema.sql`（variant / channel / price_record / market_event / tuan_order / fetch_log）
  + `database/seed.sql`（真实价格与成交，幂等，用户自行执行）。
- **唯一可用数据源 = 苹果团 appletuan.com/quotes/1**（服务端渲染，价格直接在 HTML 表格里）：
  ① 报价表 国行/港行 × 8 配置 × 4 色 → 当日快照（每容量取四色最低）
  ② 成交记录（订单流，含时间/配置/实付）→ 按日取最低成交价回填历史，撑起走势图
  坑：页面里有「囯行」错别字（解析时要宽松匹配）；`?page=2+` 内容与第 1 页重复（程序去重）。
- **全部其它自动抓取实测不可行**（别再尝试）：Apple 官网页 JS 渲染无价格；京东 p.3.cn 接口 TLS 被拒、
  商品页/搜索接口均无价格；拼多多/得物/天猫无公开接口。官方价在库里维护。
- `data/prices.json` 防覆盖：deploy.yml 在 `git reset --hard` 后执行
  `git update-index --skip-worktree data/prices.json`，仓库版仅作首次种子。

## 前端定位（2026-10-14 调整）
- **价格走势图是首页（默认路由 `#/`）**：统计摘要（区间涨跌/最低/最高/数据点）+ 时间范围切换（全部/30天/7天）
  + SVG 折线（区间最低点标注 + 大事记竖虚线）+ **真实成交记录表**（订单号脱敏）。
- 其余路由：`#/compare` 各渠道比价、`#/events` 行情大事记、`#/watch` 我的关注（localStorage）。
- **公开的「数据维护」页已删除**（用户要求：维护只能管理员看到），改由服务器端 `/admin/` 后台承担。
- 渠道新增 `tuan-g`（苹果团国行）/ `tuan-hk`（苹果团港行），配色见 CH_COLOR。
- 验证：jsdom 26 项 DOM 测试全部通过（脚本在 /tmp/smoke2.js，含 fetch shim）。


## 现有应用：果价雷达（2026-10 起）

- `index.html` + `styles.css` + `app.js` 单页应用，hash 路由；数据源 `data/prices.json`。
- 5 个视图：比价总览 / 价格走势 / 行情大事记 / 我的关注 / 数据维护。
- 走势图是手写 SVG，不引外部 JS 库（保持零依赖、可离线）。
- 「我的关注」存 localStorage（无账号体系）；「数据维护」页导出 prices.json 后覆盖仓库提交。
- 数据结构：`variants`（机型配置 + 官方价）、`channels`（渠道）、`records`（价格时间序列）、`events`（行情事件）。
- 若日后想加**注册登录 / 在线后台**，需要重新开通 WorkBuddy 云服务；届时把 `data.js` 的数据层
  从 fetch JSON 换成 `cloud.database`，并注意云服务**校验网站域名（精确 Origin）**。

## 用户偏好
- 中文交流，要结论也要可验证的结果；不喜欢华而不实的东西（原静态海报被评价为「鸡肋」）。
- 关心 charleslee.fun 这个域名的使用方式。
