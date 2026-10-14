-- ============================================================
-- 果价雷达 · 数据库设计（MySQL 5.7+/8.0 兼容）
-- 表结构与前端 data/prices.json 字段一一对应：
--   variant.code   -> variants[].id
--   channel.code   -> channels[].id
--   price_record   -> records[]
--   market_event   -> events[]
-- 原则：price_record 只增不改，每天每配置每渠道一条快照（存全历史）。
-- ============================================================

-- 机型配置（code 即前端 variant id，如 pro-256）
CREATE TABLE IF NOT EXISTS variant (
  id             INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code           VARCHAR(30)  NOT NULL COMMENT 'pro-256 / max-1024 ...',
  series         VARCHAR(50)  NOT NULL COMMENT 'iPhone 18 Pro / Pro Max',
  storage        VARCHAR(20)  NOT NULL COMMENT '256GB/1TB/...',
  gb             INT UNSIGNED NOT NULL,
  official_price INT UNSIGNED NOT NULL COMMENT '官网定价（元）',
  release_date   DATE NOT NULL,
  status         TINYINT NOT NULL DEFAULT 1 COMMENT '1在售 0停售',
  UNIQUE KEY uk_variant_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机型配置';

-- 渠道（code 即前端 channel id）
CREATE TABLE IF NOT EXISTS channel (
  id        INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code      VARCHAR(30) NOT NULL COMMENT 'apple/jd/tmall/pdd/dewu/idle',
  name      VARCHAR(50) NOT NULL,
  kind      VARCHAR(20) NOT NULL DEFAULT '3rd' COMMENT 'official/3rd',
  sort      TINYINT NOT NULL DEFAULT 99,
  service   VARCHAR(200) DEFAULT NULL COMMENT '服务说明：免息/退换政策',
  buy_url   VARCHAR(300) DEFAULT NULL,
  status    TINYINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_channel_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售渠道';

-- 价格快照（核心表：同日同配置同渠道唯一，重复执行走 ON DUPLICATE KEY UPDATE）
CREATE TABLE IF NOT EXISTS price_record (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  variant_id   INT UNSIGNED NOT NULL,
  channel_id   INT UNSIGNED NOT NULL,
  trade_date   DATE NOT NULL,
  price        INT UNSIGNED NOT NULL COMMENT '实际到手价（元）',
  in_stock     TINYINT NOT NULL DEFAULT 1,
  promo        VARCHAR(200) DEFAULT NULL COMMENT '优惠说明：24期免息/百亿补贴',
  source       VARCHAR(300) DEFAULT NULL COMMENT '来源：crawler-apple / crawler-jd / 人工录入',
  collected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_vct (variant_id, channel_id, trade_date),
  KEY idx_date (trade_date),
  CONSTRAINT fk_pr_variant FOREIGN KEY (variant_id) REFERENCES variant(id),
  CONSTRAINT fk_pr_channel FOREIGN KEY (channel_id) REFERENCES channel(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格历史快照';

-- 行情大事记（发布/首销/补贴退潮，前端走势图标注）
CREATE TABLE IF NOT EXISTS market_event (
  id      INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ev_date DATE NOT NULL,
  kind    VARCHAR(20) NOT NULL DEFAULT 'milestone' COMMENT 'launch/price/milestone',
  title   VARCHAR(100) NOT NULL,
  body    VARCHAR(500) DEFAULT NULL,
  KEY idx_evdate (ev_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行情大事记';

-- 任务运行日志（排查抓取失败）
CREATE TABLE IF NOT EXISTS fetch_log (
  id      BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  run_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status  VARCHAR(10) NOT NULL COMMENT 'OK/PARTIAL/FAIL',
  message VARCHAR(1000) DEFAULT NULL,
  records INT UNSIGNED DEFAULT 0 COMMENT '本次写入条数'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务运行日志';

-- 真实成交记录（来源：苹果团订单流，前端「真实成交记录」直接展示）
CREATE TABLE IF NOT EXISTS tuan_order (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  order_no   VARCHAR(24) NOT NULL COMMENT '苹果团订单号',
  trade_at   DATETIME NOT NULL COMMENT '成交时间',
  variant_id INT UNSIGNED NOT NULL,
  channel_id INT UNSIGNED NOT NULL,
  color      VARCHAR(30) DEFAULT NULL,
  list_price INT UNSIGNED NOT NULL COMMENT '官网价',
  paid       DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  UNIQUE KEY uk_order (order_no),
  KEY idx_trade_at (trade_at),
  CONSTRAINT fk_to_variant FOREIGN KEY (variant_id) REFERENCES variant(id),
  CONSTRAINT fk_to_channel FOREIGN KEY (channel_id) REFERENCES channel(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='苹果团真实成交记录';

-- ============================================================
-- 初始数据（与仓库 data/prices.json 当前内容一致，作为种子）
-- ============================================================

INSERT INTO variant (code, series, storage, gb, official_price, release_date) VALUES
  ('pro-256',  'iPhone 18 Pro',     '256GB',  256,  9999, '2026-09-18'),
  ('pro-512',  'iPhone 18 Pro',     '512GB',  512, 11999, '2026-09-18'),
  ('pro-1024', 'iPhone 18 Pro',     '1TB',   1024, 15499, '2026-09-18'),
  ('pro-2048', 'iPhone 18 Pro',     '2TB',   2048, 20499, '2026-09-18'),
  ('max-256',  'iPhone 18 Pro Max', '256GB',  256, 10999, '2026-09-18'),
  ('max-512',  'iPhone 18 Pro Max', '512GB',  512, 12999, '2026-09-18'),
  ('max-1024', 'iPhone 18 Pro Max', '1TB',   1024, 16499, '2026-09-18'),
  ('max-2048', 'iPhone 18 Pro Max', '2TB',   2048, 21499, '2026-09-18')
ON DUPLICATE KEY UPDATE official_price = VALUES(official_price);

INSERT INTO channel (code, name, kind, sort, service, buy_url) VALUES
  ('apple', 'Apple 官网', 'official', 1, '14 天激活可退，最长 24 期免息', 'https://www.apple.com.cn/shop/buy-iphone/iphone-18-pro/'),
  ('tmall', 'Apple 天猫官方旗舰店', 'official', 2, '官方直营，支持以旧换新', 'https://apple.tmall.com/'),
  ('jd',    '京东自营', '3rd', 3, '24 期免息，以旧换新补贴至高 900 元', 'https://item.jd.com/100012043978.html'),
  ('pdd',   '拼多多百亿补贴', '3rd', 4, '百亿补贴，价格波动大', 'https://mobile.yangkeduo.com/'),
  ('dewu',  '得物', '3rd', 5, '现货溢价波动大', 'https://www.dewu.com/'),
  ('idle',  '闲鱼(个人转卖)', '3rd', 6, '个人卖家报价，注意验机', 'https://www.goofish.com/'),
  ('tuan-g',  '苹果团 · 国行现货', '3rd', 7, '深圳现货，实时报价，排单发货', 'https://appletuan.com/quotes/1'),
  ('tuan-hk', '苹果团 · 港行现货', '3rd', 8, '港行现货，可直营店保修', 'https://appletuan.com/quotes/1')
ON DUPLICATE KEY UPDATE name = VALUES(name), sort = VALUES(sort), service = VALUES(service), buy_url = VALUES(buy_url);

INSERT INTO market_event (ev_date, kind, title, body) VALUES
  ('2026-09-10', 'launch', 'iPhone 18 Pro 系列发布，国行全线上调',
   '苹果秋季发布会推出 iPhone 18 Pro / Pro Max。相比 iPhone 17 Pro，256GB 与 512GB 上调 1000 元，1TB 上调 2500 元，并新增 2TB 版本。同步下架 iPhone 17 Pro 系列。'),
  ('2026-09-12', 'milestone', '预售开启，得物现货溢价明显',
   '预售首日得物 Pro Max 2TB 银色成交均价达 23844 元，较官网溢价 2345 元。'),
  ('2026-09-18', 'launch', '正式发售，首销日官网断货',
   '全渠道开售，官网 2TB 版本当日无货，线下渠道开始加价。'),
  ('2026-10-01', 'price', '拼多多百亿补贴直降 1000',
   '拼多多百亿补贴 Pro 256GB 降至 8999 元（官网价 9999），Pro Max 256GB 降至 9999 元。'),
  ('2026-10-12', 'price', '得物溢价回落，Pro 系列线下无人回收',
   '得物 Pro Max 2TB 银色回落至 21300 元；黄牛对新 Pro 系列暂停回收。')
ON DUPLICATE KEY UPDATE title = VALUES(title), body = VALUES(body);
