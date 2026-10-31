const fs = require('fs');
const https = require('https');

function get(url) {
  return new Promise((resolve) => {
    const req = https.get(url, { timeout: 30000, rejectUnauthorized: false }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    req.on('error', () => resolve({ status: 0, body: '' }));
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: '' }); });
  });
}

// 几个大样本插件
const PLUGINS = [
  ['EssentialsX', 858],
  ['Vault', 887],
  ['WorldEdit', 3328],
  ['ProtocolLib', 1453],
  ['Citizens', 2463],
  ['CoreProtect', 2876],
  ['GriefPrevention', 2608],
  ['Dynmap', 619],
  ['AuthMe', 2802],
  ['Multiverse-Core', 7765],
];

// 把 "1.21.4" / "1.21.11 Unobfuscated" / "26.4" 归一化成主版本
function major(name) {
  const s = String(name).trim();
  // 26.x 系列
  let m = s.match(/^26\.(\d+)/);
  if (m) return '26.' + m[1];
  // 1.x.y
  m = s.match(/^1\.(\d+)/);
  if (m) return '1.' + m[1];
  return 'other';
}

(async () => {
  const agg = new Map();   // major -> servers
  const raw = new Map();   // 原始版本名 -> servers
  let totalServers = 0;
  const perPlugin = [];

  for (const [name, id] of PLUGINS) {
    const r = await get(`https://bstats.org/api/v1/plugins/${id}/charts/minecraftVersion/data`);
    if (r.status !== 200) { perPlugin.push([name, 'HTTP ' + r.status, 0]); continue; }
    let data;
    try { data = JSON.parse(r.body); } catch { perPlugin.push([name, 'parse fail', 0]); continue; }
    if (!Array.isArray(data) || !data.length) { perPlugin.push([name, 'empty', 0]); continue; }
    let sum = 0;
    for (const row of data) {
      const y = +row.y || 0;
      sum += y;
      const mm = major(row.name);
      agg.set(mm, (agg.get(mm) || 0) + y);
      raw.set(row.name, (raw.get(row.name) || 0) + y);
    }
    totalServers += sum;
    perPlugin.push([name, 'ok', sum]);
  }

  console.log('=== 各插件样本量 ===');
  for (const [n, s, c] of perPlugin) console.log(`  ${n.padEnd(18)} ${s.padEnd(8)} ${c} 台`);
  console.log(`\n合计服务器样本: ${totalServers} 台\n`);

  console.log('=== 按主版本聚合 ===');
  const sorted = [...agg.entries()].sort((a, b) => b[1] - a[1]);
  for (const [v, c] of sorted) {
    console.log(`  ${v.padEnd(8)} ${String(c).padStart(6)} 台  ${(c / totalServers * 100).toFixed(1)}%`);
  }

  // 关键分组
  const g = (pred) => sorted.filter(([v]) => pred(v)).reduce((s, [, c]) => s + c, 0);
  const pre = g(v => v.startsWith('1.') && +v.split('.')[1] < 21);
  const j21 = g(v => v.startsWith('1.') && +v.split('.')[1] >= 21);
  const v26 = g(v => v.startsWith('26.'));
  console.log('\n=== 决策相关分组 ===');
  console.log(`  1.20 及更早（本插件 api-version=1.21 已不支持）: ${pre} 台 ${(pre / totalServers * 100).toFixed(1)}%`);
  console.log(`  1.21.x（Java 21 区间）                      : ${j21} 台 ${(j21 / totalServers * 100).toFixed(1)}%`);
  console.log(`  26.x（需 Java 25）                          : ${v26} 台 ${(v26 / totalServers * 100).toFixed(1)}%`);

  console.log('\n=== 原始版本名 Top 20 ===');
  [...raw.entries()].sort((a, b) => b[1] - a[1]).slice(0, 20)
    .forEach(([v, c]) => console.log(`  ${String(v).padEnd(24)} ${c}`));
})();
