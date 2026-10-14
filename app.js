/* ============================================================
   果价雷达 · iPhone 18 Pro 全网比价
   纯静态站点：数据来自 data/prices.json（改文件 → 提交 → 自动上线）
   ============================================================ */
'use strict';

var DATA = null;
var CH_COLOR = {
  apple:  '#0b63c5',
  tmall:  '#e0662a',
  jd:     '#b81d5b',
  unicom: '#0f8f95',
  pdd:    '#7a5af8',
  dewu:   '#8a6d3b'
};
var KIND_LABEL = {
  official: '官方', self: '自营', subsidy: '补贴', operator: '运营商', resale: '二级市场'
};
var TODAY = new Date().toISOString().slice(0, 10);

var state = {
  series: 'iPhone 18 Pro',
  variantId: 'pro-256',
  range: 0,              // 0 = 全部
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
  { hash: '#/',       label: '比价总览', view: viewOverview },
  { hash: '#/trend',  label: '价格走势', view: viewTrend },
  { hash: '#/events', label: '行情大事记', view: viewEvents },
  { hash: '#/watch',  label: '我的关注', view: viewWatch },
  { hash: '#/admin',  label: '数据维护', view: viewAdmin }
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
  if (route.hash === '#/trend') mountChart();
  if (route.hash === '#/watch') mountWatch();
  if (route.hash === '#/admin') mountAdmin();
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

/* ---------------- 视图 2：价格走势 ---------------- */
function viewTrend() {
  return '' +
  '<section class="hero"><h1>价格走势</h1>' +
    '<p class="sub">同一个配置在各渠道的价格随时间的走向。点击图例可临时隐藏某条线。</p>' +
    '<div class="selector">' +
      '<div class="field"><label>机型</label><div class="seg" id="seg-series">' +
        ['iPhone 18 Pro', 'iPhone 18 Pro Max'].map(function (s) {
          return '<button data-series="' + s + '" class="' + (s === state.series ? 'on' : '') + '">' + s + '</button>';
        }).join('') + '</div></div>' +
      '<div class="field"><label>存储容量</label><div class="seg tight" id="seg-storage">' +
        variantsOfSeries(state.series).map(function (x) {
          return '<button data-variant="' + x.id + '" class="' + (x.id === state.variantId ? 'on' : '') + '">' + x.storage + '</button>';
        }).join('') + '</div></div>' +
    '</div>' +
  '</section>' +
  '<section class="sect"><div class="card chart-box">' +
    '<div class="chart-legend" id="legend"></div>' +
    '<svg class="chart" id="chart" viewBox="0 0 1000 360" preserveAspectRatio="none"></svg>' +
    '<p style="color:var(--faint);font-size:12.5px;margin:10px 4px 4px">折线为各渠道「成交/标价」记录点之间连线，非连续行情；缺口表示该渠道未记录到数据。</p>' +
  '</div></section>';
}

function seriesFor(vid) {
  var out = {};
  channelsSorted().forEach(function (c) { out[c.id] = { ch: c, pts: [] }; });
  DATA.records.forEach(function (r) {
    if (r.variant !== vid || !out[r.channel]) return;
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

  // 折线
  series.forEach(function (s) {
    var col = CH_COLOR[s.ch.id] || '#666';
    if (s.pts.length > 1) {
      var dstr = s.pts.map(function (p) { return (X(p.x)).toFixed(1) + ',' + Y(p.y).toFixed(1); }).join(' ');
      out += '<polyline points="' + dstr + '" fill="none" stroke="' + col + '" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>';
    }
    s.pts.forEach(function (p) {
      out += '<circle cx="' + X(p.x).toFixed(1) + '" cy="' + Y(p.y).toFixed(1) + '" r="9" fill="transparent" ' +
             'data-tip="' + esc(s.ch.name + '｜' + p.x + '｜¥' + yuan(p.y) + (p.rec.promo ? '｜' + p.rec.promo : '')) + '"/>';
      out += '<circle cx="' + X(p.x).toFixed(1) + '" cy="' + Y(p.y).toFixed(1) + '" r="3.6" fill="#fff" stroke="' + col + '" stroke-width="2.2" pointer-events="none"/>';
    });
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

/* ---------------- 视图 5：数据维护 ---------------- */
function viewAdmin() {
  var head = '<th>机型 / 容量</th><th>官方价</th>' + channelsSorted().map(function (c) {
    return '<th>' + esc(c.name) + '</th>';
  }).join('') + '<th>较上次</th>';

  var body = DATA.variants.map(function (v) {
    var map = latestByChannel(v.id);
    return '<tr data-v="' + v.id + '">' +
      '<td class="vname">' + esc(v.series.replace('iPhone 18 Pro', 'Pro')) + ' ' + esc(v.storage) + '</td>' +
      '<td class="mono" style="color:var(--muted)">' + yuan(v.official) + '</td>' +
      channelsSorted().map(function (c) {
        var r = map[c.id];
        var ph = r ? r.price : v.official;
        return '<td><input type="number" step="1" data-ch="' + c.id + '" data-vid="' + v.id + '" value="' + ph + '" ' +
               'title="上次记录 ' + (r ? r.date + ' ¥' + yuan(r.price) : '无') + '"></td>';
      }).join('') +
      '<td class="diff" data-diff="' + v.id + '">—</td>' +
    '</tr>';
  }).join('');

  return '<section class="hero"><h1>数据维护</h1>' +
    '<p class="sub">把今天各渠道的实际到手价填进去，一键生成新的 <span class="mono">prices.json</span>，替换仓库文件并提交，网站就会自动更新。</p></section>' +

    '<section class="sect"><div class="notice">' +
      '<span>ℹ️</span><div>这个站点是<b>纯静态</b>的：数据文件在仓库里，<b>改文件 → git 提交 → 自动部署</b>，不依赖任何服务器或数据库。' +
      '下面的表格已经帮你预填了每条渠道的上次价格，<b>只改变动的那几个格子</b>就行，没动的会原样保留。</div></div></section>' +

    '<section class="sect">' +
      '<div class="sect-head"><h2>今日录入</h2><span class="hint">记录日期与来源会写进新条目</span></div>' +
      '<div class="card grid-wrap" style="padding:14px 16px">' +
        '<table class="entry"><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody></table>' +
        '<div class="row-actions">' +
          '<div class="field"><label>记录日期</label><input id="a-date" type="date" value="' + TODAY + '"></div>' +
          '<div class="field"><label>优惠说明（可选，套用到本次新增条目）</label><input id="a-promo" placeholder="例如：百亿补贴直降 1000 元" style="width:280px"></div>' +
          '<div class="field"><label>来源说明</label><input id="a-source" value="人工录入" style="width:170px"></div>' +
          '<button class="btn" id="a-gen">生成 prices.json</button>' +
        '</div>' +
        '<div id="a-out" style="margin-top:18px"></div>' +
      '</div>' +
    '</section>' +

    '<section class="sect"><div class="sect-head"><h2>当前数据概览</h2></div>' +
      '<div class="card" style="padding:18px 22px;font-size:14px;line-height:2">' +
        '配置文件：<span class="mono">data/prices.json</span><br>' +
        '机型配置：' + DATA.variants.length + ' 个　·　渠道：' + DATA.channels.length + ' 个　·　价格记录：' + DATA.records.length + ' 条　·　事件：' + DATA.events.length + ' 条<br>' +
        '数据更新日：<b>' + esc(DATA.meta.updatedAt) + '</b>' +
      '</div>' +
    '</section>';
}

function mountAdmin() {
  function refreshDiff() {
    $$('table.entry tbody tr').forEach(function (tr) {
      var vid = tr.getAttribute('data-v');
      var map = latestByChannel(vid);
      var changed = 0;
      $$('input[data-ch]', tr).forEach(function (inp) {
        var r = map[inp.getAttribute('data-ch')];
        var prev = r ? r.price : null;
        var now = parseInt(inp.value, 10);
        if (prev !== null && now !== prev) changed++;
      });
      var cell = $('[data-diff="' + vid + '"]', tr);
      cell.textContent = changed ? changed + ' 项变动' : '—';
      cell.style.color = changed ? 'var(--accent)' : 'var(--faint)';
    });
  }
  $$('table.entry input').forEach(function (i) { i.addEventListener('input', refreshDiff); });

  $('#a-gen').onclick = function () {
    var date = $('#a-date').value || TODAY;
    var promo = $('#a-promo').value.trim();
    var source = $('#a-source').value.trim() || '人工录入';
    var added = 0;

    var out = JSON.parse(JSON.stringify(DATA));
    $$('table.entry tbody tr').forEach(function (tr) {
      var vid = tr.getAttribute('data-v');
      var map = latestByChannel(vid);
      $$('input[data-ch]', tr).forEach(function (inp) {
        var cid = inp.getAttribute('data-ch');
        var val = parseInt(inp.value, 10);
        if (!val || val <= 0) return;
        var r = map[cid];
        if (r && r.price === val && !promo) return;   // 没变且无新说明 → 不产生新记录
        out.records.push({
          variant: vid, channel: cid, date: date, price: val,
          inStock: true, promo: promo, source: source
        });
        added++;
      });
    });
    out.records.sort(function (a, b) {
      if (a.date !== b.date) return a.date < b.date ? -1 : 1;
      if (a.variant !== b.variant) return a.variant < b.variant ? -1 : 1;
      return a.channel < b.channel ? -1 : 1;
    });
    out.meta.updatedAt = date;
    var json = JSON.stringify(out, null, 2);

    var blob = new Blob([json], { type: 'application/json' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url; a.download = 'prices.json'; a.click();
    setTimeout(function () { URL.revokeObjectURL(url); }, 4000);

    $('#a-out').innerHTML =
      '<div class="notice" style="background:var(--down-soft);border-color:#cbe9d7;color:#0b5c34">' +
        '<span>✅</span><div>已生成并下载 <b>prices.json</b>，本次新增 <b>' + added + '</b> 条记录，数据更新日改为 <b>' + esc(date) + '</b>。<br>' +
        '把它覆盖到仓库的 <span class="mono">data/prices.json</span>，提交推送，线上就会自动更新。</div></div>' +
      '<div style="margin:14px 0 6px;font-size:13px;color:var(--muted)">文件预览（前 60 行）：</div>' +
      '<div class="code">' + esc(json.split('\n').slice(0, 60).join('\n')) + '</div>';
    $('#a-out').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
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
