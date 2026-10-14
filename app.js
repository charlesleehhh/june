/* ============================================================
   果价雷达 · iPhone 18 Pro 全网比价
   纯静态站点：数据来自 data/prices.json（改文件 → 提交 → 自动上线）
   ============================================================ */
'use strict';

var DATA = null;
var CH_COLOR = {
  'tuan-hk': '#0b63c5',
  'tuan-g':  '#0f8f95',
  apple:     '#b81d5b',
  tmall:     '#e0662a',
  jd:        '#c9a227',
  pdd:       '#7a5af8',
  dewu:      '#8a6d3b',
  idle:      '#6b7280'
};
var KIND_LABEL = {
  official: '官方', self: '自营', subsidy: '补贴', operator: '运营商', resale: '二级市场'
};
var TODAY = new Date().toISOString().slice(0, 10);

var state = {
  series: 'iPhone 18 Pro',
  variantId: 'pro-256',
  range: 0,              // 0 = 全部；其余为「近 N 天」
  hidden: {}             // 走势图隐藏的渠道
};

/* ---------------- 基础工具 ---------------- */
function $(s, r) { return (r || document).querySelector(s); }
function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}
function yuan(n) { return Number(n).toLocaleString('zh-CN'); }
function variant(id) { return DATA.variants.filter(function (v) { return v.id === id; })[0]; }
function channel(id) { return DATA.channels.filter(function (c) { return c.id === id; })[0]; }
function channelsSorted() {
  return DATA.channels.slice().sort(function (a, b) { return a.sort - b.sort; });
}
function variantsOfSeries(s) { return DATA.variants.filter(function (v) { return v.series === s; }); }

/* 某配置下、每个渠道的最新一条记录 */
function latestByChannel(vid) {
  var map = {};
  DATA.records.forEach(function (r) {
    if (r.variant !== vid) return;
    var cur = map[r.channel];
    if (!cur || r.date > cur.date) map[r.channel] = r;
  });
  return map;
}
/* 某配置的最低价渠道 */
function bestOf(vid) {
  var map = latestByChannel(vid), best = null;
  channelsSorted().forEach(function (c) {
    var r = map[c.id];
    if (!r || r.inStock === false) return;
    if (!best || r.price < best.rec.price) best = { ch: c, rec: r };
  });
  return best;
}
function deltaHTML(price, official, opts) {
  opts = opts || {};
  var d = price - official;
  if (d === 0) return '<span class="delta flat">持平官网</span>';
  var cls = d < 0 ? 'down' : 'up';
  var word = d < 0 ? '低' : '高';
  var txt = (opts.short ? '' : (d < 0 ? '比官网' : '比官网')) + word + ' ' + yuan(Math.abs(d));
  return '<span class="delta ' + cls + '">' + txt + '</span>';
}
function dateShort(d) { return d.slice(5).replace('-', '/'); }

/* ---------------- 路由 ---------------- */
var ROUTES = [
  { hash: '#/',          label: '价格走势', view: viewTrend },
  { hash: '#/compare',   label: '各渠道比价', view: viewOverview },
  { hash: '#/events',    label: '行情大事记', view: viewEvents },
  { hash: '#/watch',     label: '我的关注', view: viewWatch }
];
function currentHash() {
  var h = location.hash || '#/';
  return ROUTES.some(function (r) { return r.hash === h; }) ? h : '#/';
}
function go(hash) { location.hash = hash; }

function renderNav() {
  var cur = currentHash();
  $('#nav').innerHTML = ROUTES.map(function (r) {
    return '<button data-go="' + r.hash + '" class="' + (r.hash === cur ? 'on' : '') + '">' + r.label + '</button>';
  }).join('');
}
function render() {
  renderNav();
  var route = ROUTES.filter(function (r) { return r.hash === currentHash(); })[0];
  $('#view').innerHTML = route.view();
  if (route.hash === '#/') mountChart();
  if (route.hash === '#/watch') mountWatch();
  window.scrollTo(0, 0);
}

/* ---------------- 视图 1：比价总览 ---------------- */
function viewOverview() {
  var v = variant(state.variantId);
  var map = latestByChannel(v.id);
  var best = bestOf(v.id);
  var rows = channelsSorted().map(function (c) {
    var r = map[c.id];
    var isBest = best && best.ch.id === c.id;
    var priceCell = r
      ? '<td class="num"><span style="font-size:16px;font-weight:600">¥' + yuan(r.price) + '</span></td>'
      : '<td class="num"><span class="oos">暂无数据</span></td>';
    var deltaCell = r ? '<td class="num">' + deltaHTML(r.price, v.official) + '</td>'
                      : '<td class="num"><span class="oos">—</span></td>';
    return '<tr class="' + (isBest ? 'best-row' : '') + '">' +
      '<td><div class="ch-name">' + esc(c.name) +
        (isBest ? '<span class="tagbest">最低</span>' : '') +
        '<span class="pill ' + c.kind + '">' + KIND_LABEL[c.kind] + '</span></div>' +
        '<div class="note">' + esc(c.service) + '</div>' +
        (r ? '<div class="src">更新于 ' + r.date + ' · 来源：' + esc(r.source) + '</div>' : '') +
      '</td>' +
      '<td>' + (r && r.promo ? esc(r.promo) : '<span class="oos">—</span>') + '</td>' +
      priceCell + deltaCell +
      '<td>' + (r ? (r.inStock === false ? '<span class="oos">缺货</span>' : '<span class="pill subsidy">有货</span>') : '<span class="oos">—</span>') + '</td>' +
    '</tr>';
  }).join('');

  var bestGap = best ? best.rec.price - v.official : 0;
  var launch = DATA.meta.launchDate || '2026-09-18';
  var spanDays = Math.max(1, Math.round((new Date() - new Date(launch)) / 86400000));

  return '' +
  '<section class="hero">' +
    '<h1>' + esc(DATA.meta.subtitle) + '</h1>' +
    '<p class="sub">把同一个配置在 6 个渠道的到手价放在一张表里 —— 谁最便宜、比官网低多少、数据来自哪里，一眼看完。</p>' +

    '<div class="selector">' +
      '<div class="field"><label>机型</label><div class="seg" id="seg-series">' +
        ['iPhone 18 Pro', 'iPhone 18 Pro Max'].map(function (s) {
          return '<button data-series="' + s + '" class="' + (s === state.series ? 'on' : '') + '">' + s + '</button>';
        }).join('') +
      '</div></div>' +
      '<div class="field"><label>存储容量</label><div class="seg tight" id="seg-storage">' +
        variantsOfSeries(state.series).map(function (x) {
          return '<button data-variant="' + x.id + '" class="' + (x.id === state.variantId ? 'on' : '') + '">' + x.storage + '</button>';
        }).join('') +
      '</div></div>' +
    '</div>' +

    '<div class="best">' +
      '<div class="card best-main">' +
        (best ? (
          '<div class="k">当前全网最低到手价 <span class="pill ' + best.ch.kind + '">' + esc(best.ch.name) + '</span></div>' +
          '<div class="price"><span class="cur">¥</span>' + yuan(best.rec.price) +
            '<span class="sub">官方价 ¥' + yuan(v.official) + '</span></div>' +
          '<div style="margin-top:14px">' + deltaHTML(best.rec.price, v.official) + '</div>' +
          '<div class="meta">' +
            '记录日期：<b>' + best.rec.date + '</b>　·　来源：<b>' + esc(best.rec.source) + '</b><br>' +
            '优惠：' + (best.rec.promo ? esc(best.rec.promo) : '—') + '<br>' +
            '售后：' + esc(best.ch.service) +
          '</div>'
        ) : '<div class="empty">该配置暂无价格记录</div>') +
      '</div>' +
      '<div class="card best-side">' +
        '<div class="stat"><span class="lab">官方价</span><span class="val">¥' + yuan(v.official) + '</span></div>' +
        '<div class="stat"><span class="lab">覆盖渠道</span><span class="val">' + Object.keys(map).length + ' / ' + DATA.channels.length + '</span></div>' +
        '<div class="stat"><span class="lab">最大价差</span><span class="val sm">' + maxSpread(v.id) + '</span></div>' +
        '<div class="stat"><span class="lab">数据更新日</span><span class="val sm">' + esc(DATA.meta.updatedAt) + '</span></div>' +
        '<div class="stat"><span class="lab">距发售</span><span class="val sm">' + spanDays + ' 天</span></div>' +
      '</div>' +
    '</div>' +
  '</section>' +

  '<section class="sect">' +
    '<div class="sect-head"><h2>' + esc(v.series) + ' ' + v.storage + ' · 各渠道比价</h2>' +
      '<span class="hint">按渠道官方排序，最低价整行高亮</span></div>' +
    '<div class="card grid-wrap"><table class="tbl">' +
      '<thead><tr><th>渠道</th><th>优惠 / 到手条件</th><th style="text-align:right">当前价</th><th style="text-align:right">对比官网</th><th>库存</th></tr></thead>' +
      '<tbody>' + rows + '</tbody>' +
    '</table></div>' +
    '<p class="hint" style="color:var(--faint);font-size:12.5px;margin:12px 2px 0">' + esc(DATA.meta.notice) + '</p>' +
  '</section>';
}
function maxSpread(vid) {
  var map = latestByChannel(vid), vals = [];
  channelsSorted().forEach(function (c) { if (map[c.id]) vals.push(map[c.id].price); });
  if (vals.length < 2) return '—';
  return '¥' + yuan(Math.max.apply(null, vals) - Math.min.apply(null, vals));
}

/* ---------------- 视图 1：价格走势（首页） ---------------- */
var RANGES = [{ label: '全部', days: 0 }, { label: '近 30 天', days: 30 }, { label: '近 7 天', days: 7 }];

function rangeStart() {
  if (!state.range) return null;
  return new Date(Date.now() - state.range * 86400000).toISOString().slice(0, 10);
}
function recordsOf(vid) {
  var from = rangeStart();
  return DATA.records.filter(function (r) {
    return r.variant === vid && (!from || r.date >= from);
  });
}
function byDate(a, b) { return a.date < b.date ? -1 : (a.date > b.date ? 1 : 0); }

/* 走势摘要：当前最低、区间涨跌、区间高低、数据点 */
function trendSummary(vid) {
  var v = variant(vid);
  var recs = recordsOf(vid);
  var byCh = {};
  recs.forEach(function (r) { (byCh[r.channel] = byCh[r.channel] || []).push(r); });
  Object.keys(byCh).forEach(function (k) { byCh[k].sort(byDate); });

  var bestCh = null, bestRec = null;
  Object.keys(byCh).forEach(function (k) {
    var last = byCh[k][byCh[k].length - 1];
    if (last.inStock === false) return;
    if (!bestRec || last.price < bestRec.price) { bestRec = last; bestCh = k; }
  });

  var lowRec = null, highRec = null;
  recs.forEach(function (r) {
    if (!lowRec || r.price < lowRec.price) lowRec = r;
    if (!highRec || r.price > highRec.price) highRec = r;
  });

  var mainCh = null, mainPts = null;
  Object.keys(byCh).forEach(function (k) {
    if (!mainPts || byCh[k].length > mainPts.length) { mainPts = byCh[k]; mainCh = k; }
  });
  var first = mainPts && mainPts.length > 1 ? mainPts[0] : null;
  var last = mainPts && mainPts.length > 1 ? mainPts[mainPts.length - 1] : null;
  var dates = recs.map(function (r) { return r.date; }).sort();
  var spanDays = dates.length ? Math.round((new Date(dates[dates.length - 1]) - new Date(dates[0])) / 86400000) : 0;

  return {
    v: v, bestCh: bestCh, bestRec: bestRec, lowRec: lowRec, highRec: highRec,
    mainCh: mainCh, first: first, last: last, points: recs.length, spanDays: spanDays,
    channels: Object.keys(byCh).length
  };
}

function viewTrend() {
  var s = trendSummary(state.variantId);
  var v = s.v;
  var drop = (s.first && s.last) ? s.last.price - s.first.price : 0;
  var dropPct = (s.first && s.first.price) ? (drop / s.first.price * 100) : 0;
  var mainName = s.mainCh ? (channel(s.mainCh) || {}).name : '';
  var d5 = function (d) { return d.slice(5).replace('-', '/'); };

  var statCards = '' +
    '<div class="card best-main">' +
      '<div class="k">当前全网最低到手价' +
        (s.bestCh ? ' <span class="pill ' + (channel(s.bestCh) || {}).kind + '">' + esc((channel(s.bestCh) || {}).name) + '</span>' : '') +
      '</div>' +
      '<div class="price"><span class="cur">¥</span>' + (s.bestRec ? yuan(s.bestRec.price) : '—') +
        '<span class="sub">官方价 ¥' + yuan(v.official) + '</span></div>' +
      '<div style="margin-top:12px">' + (s.bestRec ? deltaHTML(s.bestRec.price, v.official) : '') + '</div>' +
      '<div class="meta">' + (s.bestRec ? '记录日期 <b>' + s.bestRec.date + '</b>　·　' + esc(s.bestRec.promo || '') : '暂无记录') + '</div>' +
    '</div>' +
    '<div class="card best-side">' +
      '<div class="stat"><span class="lab">区间涨跌</span><span class="val sm">' +
        (s.first && s.last ? ((drop < 0 ? '↓' : '↑') + ' ¥' + yuan(Math.abs(drop)) + '（' + dropPct.toFixed(1) + '%）') : '—') +
        '</span></div>' +
      '<div class="stat"><span class="lab">统计区间</span><span class="val sm">' + s.spanDays + ' 天</span></div>' +
      '<div class="stat"><span class="lab">区间最低</span><span class="val sm">¥' + (s.lowRec ? yuan(s.lowRec.price) : '—') +
        (s.lowRec ? ' · ' + d5(s.lowRec.date) : '') + '</span></div>' +
      '<div class="stat"><span class="lab">区间最高</span><span class="val sm">¥' + (s.highRec ? yuan(s.highRec.price) : '—') +
        (s.highRec ? ' · ' + d5(s.highRec.date) : '') + '</span></div>' +
      '<div class="stat"><span class="lab">数据点 / 渠道</span><span class="val sm">' + s.points + ' / ' + s.channels + '</span></div>' +
    '</div>';

  return '' +
  '<section class="hero">' +
    '<h1>' + esc(v.series) + ' ' + esc(v.storage) + ' 价格走势</h1>' +
    '<p class="sub">' +
      (s.first && s.last
        ? '以 <b>' + esc(mainName) + '</b> 为主线：' + d5(s.first.date) + ' 的 ¥' + yuan(s.first.price) +
          ' → ' + d5(s.last.date) + ' 的 ¥' + yuan(s.last.price) + '，' + s.spanDays + ' 天' +
          (drop < 0 ? '下跌' : '上涨') + ' <b>¥' + yuan(Math.abs(drop)) + '</b>（' + dropPct.toFixed(1) + '%）。'
        : '') +
      '数据来自苹果团实时报价与真实成交记录。' +
    '</p>' +
    '<div class="selector">' +
      '<div class="field"><label>机型</label><div class="seg" id="seg-series">' +
        ['iPhone 18 Pro', 'iPhone 18 Pro Max'].map(function (x) {
          return '<button data-series="' + x + '" class="' + (x === state.series ? 'on' : '') + '">' + x + '</button>';
        }).join('') + '</div></div>' +
      '<div class="field"><label>存储容量</label><div class="seg tight" id="seg-storage">' +
        variantsOfSeries(state.series).map(function (x) {
          return '<button data-variant="' + x.id + '" class="' + (x.id === state.variantId ? 'on' : '') + '">' + x.storage + '</button>';
        }).join('') + '</div></div>' +
      '<div class="field"><label>时间范围</label><div class="seg tight" id="seg-range">' +
        RANGES.map(function (r) {
          return '<button data-range="' + r.days + '" class="' + (r.days === state.range ? 'on' : '') + '">' + r.label + '</button>';
        }).join('') + '</div></div>' +
    '</div>' +
    '<div class="best">' + statCards + '</div>' +
  '</section>' +

  '<section class="sect"><div class="card chart-box">' +
    '<div class="chart-legend" id="legend"></div>' +
    '<svg class="chart" id="chart" viewBox="0 0 1000 360" preserveAspectRatio="none"></svg>' +
    '<p style="color:var(--faint);font-size:12.5px;margin:10px 4px 4px">' +
      '实心圆点是各渠道在区间内的最低价，虚线竖线是行情大事记节点；点击图例可隐藏某条线，悬停查看明细。</p>' +
  '</div></section>' +

  '<section class="sect">' +
    '<div class="sect-head"><h2>真实成交记录</h2><span class="hint">苹果团现货订单 · 按成交时间倒序</span></div>' +
    ordersTable() +
  '</section>';
}

function ordersTable() {
  var list = (DATA.orders || []).slice(0, 14);
  if (!list.length) {
    return '<div class="card"><div class="empty">暂无成交记录（下次抓取后会显示）</div></div>';
  }
  var rows = list.map(function (o) {
    var v = variant(o.variant) || { series: o.variant, storage: '' };
    var c = channel(o.channel) || { name: o.channel, kind: 'resale' };
    var save = o.listPrice - o.paid;
    return '<tr>' +
      '<td class="mono">' + esc(o.at.slice(5, 16)) + '</td>' +
      '<td>' + esc(v.series.replace('iPhone ', '')) + ' ' + esc(v.storage) + '</td>' +
      '<td>' + esc(o.color || '—') + '</td>' +
      '<td><span class="pill ' + c.kind + '">' + esc(c.name) + '</span></td>' +
      '<td class="num mono">' + yuan(o.listPrice) + '</td>' +
      '<td class="num"><b>¥' + yuan(o.paid) + '</b></td>' +
      '<td class="num"><span class="delta down">省 ' + yuan(Math.round(save)) + '</span></td>' +
      '<td class="mono" style="color:var(--faint);font-size:12px">' + esc(o.orderNo) + '</td>' +
    '</tr>';
  }).join('');
  return '<div class="card grid-wrap"><table class="tbl">' +
    '<thead><tr><th>成交时间</th><th>配置</th><th>颜色</th><th>渠道</th>' +
    '<th style="text-align:right">官网价</th><th style="text-align:right">实付</th>' +
    '<th style="text-align:right">较官网</th><th>订单号</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table></div>';
}

function seriesFor(vid) {
  var out = {};
  channelsSorted().forEach(function (c) { out[c.id] = { ch: c, pts: [] }; });
  recordsOf(vid).forEach(function (r) {
    if (!out[r.channel]) return;
    out[r.channel].pts.push({ x: r.date, y: r.price, rec: r });
  });
  Object.keys(out).forEach(function (k) {
    out[k].pts.sort(function (a, b) { return a.x < b.x ? -1 : 1; });
  });
  return Object.keys(out).filter(function (k) { return out[k].pts.length; })
    .map(function (k) { return out[k]; });
}

function mountChart() {
  var v = variant(state.variantId);
  var all = seriesFor(v.id);
  var have = {};
  all.forEach(function (s) { have[s.ch.id] = true; });
  var legend = $('#legend');
  // 无记录的渠道也列出来（灰显），让「哪里还没数据」一目了然
  legend.innerHTML = channelsSorted().map(function (c) {
    if (!have[c.id]) {
      return '<span class="nodata" title="该渠道尚未记录此配置的价格"><i style="background:' + CH_COLOR[c.id] + '"></i>' +
        esc(c.name) + '</span>';
    }
    return '<span data-toggle="' + c.id + '" class="' + (state.hidden[c.id] ? 'off' : '') + '">' +
      '<i style="background:' + CH_COLOR[c.id] + '"></i>' + esc(c.name) + '</span>';
  }).join('');
  legend.onclick = function (e) {
    var t = e.target.closest('[data-toggle]');
    if (!t) return;
    var id = t.getAttribute('data-toggle');
    state.hidden[id] = !state.hidden[id];
    mountChart();
  };
  var shown = all.filter(function (s) { return !state.hidden[s.ch.id]; });
  $('#chart').innerHTML = drawChart(shown, v);
  bindTip($('#chart'));
}

function drawChart(series, v) {
  var W = 1000, H = 360, PL = 74, PR = 26, PT = 18, PB = 38;
  var iw = W - PL - PR, ih = H - PT - PB;

  var xs = [], ys = [];
  series.forEach(function (s) {
    if (s.pts.length) { xs.push(s.pts[0].x); xs.push(s.pts[s.pts.length - 1].x); }
    s.pts.forEach(function (p) { ys.push(p.y); });
  });
  if (!xs.length || !ys.length) return '<text x="500" y="180" text-anchor="middle" class="axis-t">暂无数据</text>';
  ys.push(v.official);

  var x0 = new Date(xs[0]).getTime(), x1 = new Date(xs[xs.length - 1]).getTime();
  if (x0 === x1) { x0 -= 86400000; x1 += 86400000; }
  var yMin = Math.min.apply(null, ys), yMax = Math.max.apply(null, ys);
  var pad = Math.max(60, Math.round((yMax - yMin) * 0.18));
  yMin -= pad; yMax += pad;
  yMin = Math.floor(yMin / 200) * 200; yMax = Math.ceil(yMax / 200) * 200;

  function X(d) { return PL + (new Date(d).getTime() - x0) / (x1 - x0) * iw; }
  function Y(p) { return PT + (yMax - p) / (yMax - yMin) * ih; }

  var out = '';

  // Y 轴刻度
  var steps = 5;
  for (var i = 0; i <= steps; i++) {
    var val = yMin + (yMax - yMin) * i / steps;
    var y = Y(val);
    out += '<line class="grid-line" x1="' + PL + '" y1="' + y.toFixed(1) + '" x2="' + (W - PR) + '" y2="' + y.toFixed(1) + '"/>';
    out += '<text class="axis-t" x="' + (PL - 10) + '" y="' + (y + 4).toFixed(1) + '" text-anchor="end">' + yuan(Math.round(val)) + '</text>';
  }

  // 官网基准线
  out += '<line x1="' + PL + '" y1="' + Y(v.official).toFixed(1) + '" x2="' + (W - PR) + '" y2="' + Y(v.official).toFixed(1) +
         '" stroke="#9aa9b3" stroke-width="1" stroke-dasharray="5 5"/>';
  out += '<text class="axis-t" x="' + (W - PR) + '" y="' + (Y(v.official) - 6).toFixed(1) + '" text-anchor="end">官方价 ' + yuan(v.official) + '</text>';

  // X 轴刻度（按天采样）
  var days = Math.round((x1 - x0) / 86400000);
  var stepD = days <= 12 ? 2 : (days <= 40 ? 7 : 15);
  for (var d = 0; d <= days; d += stepD) {
    var ds = new Date(x0 + d * 86400000).toISOString().slice(0, 10);
    out += '<text class="axis-t" x="' + X(ds).toFixed(1) + '" y="' + (H - 14) + '" text-anchor="middle">' + dateShort(ds) + '</text>';
  }

  // 行情大事记：竖虚线标注，让「为什么涨/跌」看得见
  (DATA.events || []).forEach(function (ev) {
    var t = new Date(ev.date).getTime();
    if (t < x0 || t > x1) return;
    var x = X(ev.date).toFixed(1);
    out += '<line x1="' + x + '" y1="' + PT + '" x2="' + x + '" y2="' + (H - PB) + '" stroke="#c2b8a3" stroke-width="1" stroke-dasharray="3 4"/>';
    out += '<circle cx="' + x + '" cy="' + (PT + 4) + '" r="10" fill="transparent" data-tip="' +
           esc(ev.date + '｜' + ev.title + (ev.body ? '｜' + ev.body : '')) + '"/>';
    out += '<circle cx="' + x + '" cy="' + (PT + 4) + '" r="3" fill="#b8a67e" pointer-events="none"/>';
  });

  // 折线 + 区间最低点标记
  var globalLow = null;
  series.forEach(function (s) {
    s.pts.forEach(function (p) { if (!globalLow || p.y < globalLow.y) globalLow = { y: p.y, x: p.x }; });
  });

  series.forEach(function (s) {
    var col = CH_COLOR[s.ch.id] || '#666';
    if (s.pts.length > 1) {
      var dstr = s.pts.map(function (p) { return (X(p.x)).toFixed(1) + ',' + Y(p.y).toFixed(1); }).join(' ');
      out += '<polyline points="' + dstr + '" fill="none" stroke="' + col + '" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>';
    }
    var low = null;
    s.pts.forEach(function (p) { if (!low || p.y < low.y) low = p; });

    s.pts.forEach(function (p) {
      out += '<circle cx="' + X(p.x).toFixed(1) + '" cy="' + Y(p.y).toFixed(1) + '" r="9" fill="transparent" ' +
             'data-tip="' + esc(s.ch.name + '｜' + p.x + '｜¥' + yuan(p.y) + (p.rec.promo ? '｜' + p.rec.promo : '') +
             (p.rec.source ? '｜' + p.rec.source : '')) + '"/>';
      out += '<circle cx="' + X(p.x).toFixed(1) + '" cy="' + Y(p.y).toFixed(1) + '" r="3.6" fill="#fff" stroke="' + col + '" stroke-width="2.2" pointer-events="none"/>';
    });

    if (low && globalLow && low.y === globalLow.y) {
      var lx = X(low.x), ly = Y(low.y);
      out += '<circle cx="' + lx.toFixed(1) + '" cy="' + ly.toFixed(1) + '" r="6" fill="none" stroke="' + col + '" stroke-width="2" pointer-events="none"/>';
      out += '<text class="axis-t" x="' + Math.min(W - PR - 96, lx + 9).toFixed(1) + '" y="' + (ly - 9).toFixed(1) +
             '" fill="' + col + '" style="font-size:12px">区间最低 ¥' + yuan(low.y) + '</text>';
    } else if (low) {
      out += '<circle cx="' + X(low.x).toFixed(1) + '" cy="' + Y(low.y).toFixed(1) + '" r="5" fill="none" stroke="' + col +
             '" stroke-width="1.6" opacity="0.65" pointer-events="none"/>';
    }
  });
  return out;
}

function bindTip(svg) {
  var tip = $('#tip');
  svg.addEventListener('mouseover', function (e) {
    var t = e.target.closest ? e.target.closest('[data-tip]') : null;
    if (!t) return;
    tip.textContent = t.getAttribute('data-tip');
    tip.classList.add('on');
  });
  svg.addEventListener('mousemove', function (e) {
    if (!tip.classList.contains('on')) return;
    tip.style.left = Math.min(window.innerWidth - tip.offsetWidth - 12, e.clientX + 14) + 'px';
    tip.style.top = Math.max(8, e.clientY - tip.offsetHeight - 12) + 'px';
  });
  svg.addEventListener('mouseout', function () { tip.classList.remove('on'); });
}

/* ---------------- 视图 3：大事记 ---------------- */
function viewEvents() {
  var evs = DATA.events.slice().sort(function (a, b) { return a.date < b.date ? 1 : -1; });
  return '<section class="hero"><h1>行情大事记</h1>' +
    '<p class="sub">发布会、预售、首销、补贴退潮 —— 价格变化的每个节点都记在案上，方便回看「为什么会涨/跌」。</p></section>' +
    '<section class="sect"><div class="card" style="padding:24px 26px"><div class="tl">' +
    evs.map(function (e) {
      return '<div class="tl-item ' + esc(e.kind) + '"><div class="tl-date">' + esc(e.date) + '</div>' +
        '<h3>' + esc(e.title) + '</h3><p>' + esc(e.body) + '</p></div>';
    }).join('') +
    '</div></div></section>';
}

/* ---------------- 视图 4：我的关注 ---------------- */
var WKEY = 'guojia.watch.v1';
function loadWatch() { try { return JSON.parse(localStorage.getItem(WKEY) || '[]'); } catch (e) { return []; } }
function saveWatch(a) { try { localStorage.setItem(WKEY, JSON.stringify(a)); } catch (e) {} }

function viewWatch() {
  var list = loadWatch();
  return '<section class="hero"><h1>我的关注</h1>' +
    '<p class="sub">给关心的配置设一个心理价位，每次打开就能看到是否已经到达。</p></section>' +
    '<section class="sect"><div class="card" style="padding:20px 22px">' +
      '<div class="form-row">' +
        '<div class="field"><label>机型配置</label><select id="w-variant">' +
          DATA.variants.map(function (v) {
            return '<option value="' + v.id + '">' + esc(v.series + ' ' + v.storage) + '　官方 ¥' + yuan(v.official) + '</option>';
          }).join('') +
        '</select></div>' +
        '<div class="field"><label>目标到手价（元）</label><input id="w-target" type="number" min="1" step="100" placeholder="例如 8999" style="width:150px"></div>' +
        '<button class="btn" id="w-add">添加关注</button>' +
      '</div>' +
      '<p style="color:var(--faint);font-size:12.5px;margin:12px 0 0">关注列表保存在本机浏览器（未接入账号系统），换设备/清缓存会丢失。</p>' +
    '</div></section>' +
    '<section class="sect"><div class="sect-head"><h2>关注清单</h2><span class="hint">共 ' + list.length + ' 项</span></div>' +
      '<div id="w-list">' + watchCards(list) + '</div>' +
    '</section>';
}
function watchCards(list) {
  if (!list.length) return '<div class="card"><div class="empty">还没有关注项 —— 在上面选个配置、填个目标价试试。</div></div>';
  return '<div class="watch-grid">' + list.map(function (w) {
    var v = variant(w.variantId);
    if (!v) return '';
    var best = bestOf(v.id);
    var cur = best ? best.rec.price : null;
    var reach = cur != null && cur <= w.target;
    return '<div class="card wcard">' +
      '<div class="top"><div><div class="name">' + esc(v.series) + ' ' + esc(v.storage) + '</div>' +
        '<div class="goal">目标 ¥' + yuan(w.target) + ' · 官方 ¥' + yuan(v.official) + '</div></div>' +
        '<button class="xbtn" data-del="' + w.id + '" title="移除">✕</button></div>' +
      '<div class="cur">' + (cur != null ? '¥' + yuan(cur) : '—') + '</div>' +
      '<div class="goal">' + (best ? '最低：' + esc(best.ch.name) + ' · ' + best.rec.date : '暂无价格记录') + '</div>' +
      '<div class="reach ' + (reach ? 'yes' : 'no') + '">' +
        (cur == null ? '等待数据' : (reach ? '已到目标价，可入手' : '还差 ¥' + yuan(cur - w.target))) + '</div>' +
    '</div>';
  }).join('') + '</div>';
}
function mountWatch() {
  $('#w-add').onclick = function () {
    var vid = $('#w-variant').value, t = parseInt($('#w-target').value, 10);
    if (!t || t <= 0) { alert('请填写一个有效的目标价'); return; }
    var list = loadWatch();
    if (list.some(function (x) { return x.variantId === vid && x.target === t; })) { alert('已经有同样的关注了'); return; }
    list.push({ id: String(Date.now()), variantId: vid, target: t });
    saveWatch(list);
    $('#view').innerHTML = viewWatch(); mountWatch();
  };
  $('#w-list').onclick = function (e) {
    var b = e.target.closest('[data-del]');
    if (!b) return;
    var id = b.getAttribute('data-del');
    var list = loadWatch().filter(function (x) { return x.id !== id; });
    saveWatch(list);
    $('#view').innerHTML = viewWatch(); mountWatch();
  };
}

/* ---------------- 全局事件 ---------------- */
document.addEventListener('click', function (e) {
  var g = e.target.closest('[data-go]');
  if (g) { go(g.getAttribute('data-go')); return; }

  var sBtn = e.target.closest('[data-series]');
  if (sBtn) {
    state.series = sBtn.getAttribute('data-series');
    state.variantId = variantsOfSeries(state.series)[0].id;
    render();
    return;
  }
  var vBtn = e.target.closest('[data-variant]');
  if (vBtn) {
    state.variantId = vBtn.getAttribute('data-variant');
    render();
    return;
  }
  var rBtn = e.target.closest('[data-range]');
  if (rBtn) {
    state.range = parseInt(rBtn.getAttribute('data-range'), 10) || 0;
    render();
    return;
  }
});
window.addEventListener('hashchange', render);

/* ---------------- 启动 ---------------- */
fetch('data/prices.json', { cache: 'no-cache' })
  .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
  .then(function (d) {
    DATA = d;
    $('#stamp').textContent = '数据更新 · ' + d.meta.updatedAt;
    $('#foot-note').textContent = d.meta.notice;
    $('#foot-time').textContent = '最后更新 ' + d.meta.updatedAt;
    render();
  })
  .catch(function (err) {
    var isFile = location.protocol === 'file:';
    $('#view').innerHTML = '<div class="card" style="margin:40px 0"><div class="empty">' +
      (isFile
        ? '当前是用本地文件方式打开的，浏览器出于安全策略不允许读取 <span class="mono">data/prices.json</span>。<br>' +
          '请用一个本地静态服务器打开，例如在项目目录执行：<br>' +
          '<span class="mono">python3 -m http.server 8000</span>，然后访问 <span class="mono">http://localhost:8000</span>。'
        : '数据加载失败（' + esc(err.message) + '）。<br>请确认 <span class="mono">data/prices.json</span> 存在且可访问。') +
      '</div></div>';
  });
