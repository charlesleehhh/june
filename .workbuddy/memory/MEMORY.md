# june 项目 · 长期备忘

## 项目性质
- 仓库 `charlesleehhh/june`（GitHub，**public**），本地路径 `/Users/charleslee/Documents/TRAE/june`。
- **仓库根目录 = charleslee.fun 的站点根目录**：服务器 `/opt/data/charleslee/github/june` 执行
  `git fetch && git reset --hard origin/main`，所以 **仓库里有什么，线上就能访问到什么**。
  在根目录放任何文件都等于公开发布 —— 敏感文件必须进 `.gitignore`。
- 部署链路：`.github/workflows/deploy.yml`，push 到 main → appleboy/ssh-action 登服务器拉代码。
  前端改动**只需 git 提交推送**，不需要在本地构建。

## 硬性约定
- **密钥一律不入库**：`github-deploy-key` / `github-deploy-key.pub` 已 `git rm --cached`，
  `.gitignore` 已覆盖。改动密钥后要同步更新 GitHub Secret `SERVER_PRIVATE_KEY`。
- 站点是**纯静态**：没有后端、没有数据库，数据放 `data/prices.json`，改文件即上线。
- 本地预览用 `python3 -m http.server`，**不要用 file:// 直接打开**（fetch 被浏览器拦截）。

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
