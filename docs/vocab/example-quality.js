const fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '..', '..');
const V = path.join(ROOT, 'docs', 'vocab');
const W = path.join(ROOT, 'src', 'main', 'resources', 'words');

const rank = new Map();
for (const l of fs.readFileSync(path.join(V, 'ngsl_stats.csv'), 'utf8')
  .split(/\r?\n/).filter(Boolean).slice(1)) {
  const m = l.match(/^"?(.*?)"?,(\d+),([\d.]+),(\d+)$/);
  if (m) rank.set(m[1].toLowerCase(), +m[2]);
}
const f2h = new Map();
for (const l of fs.readFileSync(path.join(V, 'ngsl_lemma.csv'), 'utf8')
  .split(/\r?\n/).filter(x => x && !x.startsWith('#'))) {
  const p = l.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (p.length) for (const f of p) if (!f2h.has(f)) f2h.set(f, p[0]);
}
const headOf = w => f2h.get(w.toLowerCase()) || w.toLowerCase();

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const EX_RE = /^\s{4}example_sentence:\s*"((?:[^"\\]|\\.)*)"/;

// 现有词库全部词形（玩家"应该认识"的词）
const known = new Set();
const entries = [];
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const txt = fs.readFileSync(path.join(W, t + '.yml'), 'utf8');
  let cur = null;
  for (const line of txt.split(/\r?\n/)) {
    let m;
    if ((m = line.match(WORD_RE))) { cur = { w: m[1], ex: null, tier: t }; entries.push(cur); continue; }
    if (cur && (m = line.match(EX_RE))) cur.ex = m[1];
  }
  let m; const re = new RegExp(WORD_RE.source, 'gm');
  while ((m = re.exec(txt)) !== null) known.add(m[1].toLowerCase());
}

// 功能词白名单：不算生词
const FUNC = new Set(('a an the i you he she it we they me him her us them my your his its our their '
  + 'this that these those am is are was were be been being have has had do does did will would shall '
  + 'should can could may might must not no yes and or but if then than so as at by for with from to of '
  + 'in on into over under about after before between during through up down out off again just very '
  + 'too also there here what who which when where why how all any some each every both few many much '
  + 'more most other another such own same only because while until since though although well now still '
  + 'even ever never always often sometimes usually today tomorrow yesterday please thanks thank ok okay '
  + 'one two three first last next new old good bad big small long short high low right left').split(/\s+/));

function tokens(s) {
  return s.toLowerCase().replace(/[^a-z' ]/g, ' ').split(/\s+/).filter(Boolean);
}

// 统计每条例句的"超纲词"数（不在词库、不在功能词、不在 NGSL 前 2000）
let dist = {};
const offenders = [];
for (const e of entries) {
  if (!e.ex) continue;
  const toks = tokens(e.ex);
  const targetHead = headOf(e.w);
  const unknown = [];
  for (const t of toks) {
    if (FUNC.has(t)) continue;
    if (known.has(t)) continue;
    if (headOf(t) === targetHead) continue;
    const r = rank.get(headOf(t));
    if (r && r <= 2000) continue;            // NGSL 前 2000 算"可接受"
    unknown.push(t);
  }
  dist[unknown.length] = (dist[unknown.length] || 0) + 1;
  if (unknown.length > 0) offenders.push({ w: e.w, tier: e.tier, ex: e.ex, unknown });
}
console.log('=== 例句超纲词分布（全 1357 词条）===');
console.log(JSON.stringify(dist));
const total = Object.values(dist).reduce((a, b) => a + b, 0);
const cleanN = dist[0] || 0;
console.log(`完全无超纲词: ${cleanN}/${total} (${(cleanN / total * 100).toFixed(1)}%)`);
console.log(`有超纲词的: ${offenders.length}`);
console.log('\n=== 超纲最多的 15 条 ===');
offenders.sort((a, b) => b.unknown.length - a.unknown.length);
for (const o of offenders.slice(0, 15)) {
  console.log(`[${o.tier[0]}] ${o.w.padEnd(12)} ${o.unknown.join(',')}  | ${o.ex}`);
}
