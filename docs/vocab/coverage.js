const fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '..', '..');
const V = path.join(ROOT, 'docs', 'vocab');
const W = path.join(ROOT, 'src', 'main', 'resources', 'words');

const freq = new Map();
for (const l of fs.readFileSync(path.join(V, 'ngsl_stats.csv'), 'utf8')
  .split(/\r?\n/).filter(Boolean).slice(1)) {
  const m = l.match(/^"?(.*?)"?,(\d+),([\d.]+),(\d+)$/);
  if (m) freq.set(m[1].toLowerCase(), +m[4]);
}
const f2h = new Map();
for (const l of fs.readFileSync(path.join(V, 'ngsl_lemma.csv'), 'utf8')
  .split(/\r?\n/).filter(x => x && !x.startsWith('#'))) {
  const p = l.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (p.length) for (const f of p) if (!f2h.has(f)) f2h.set(f, p[0]);
}
const headOf = w => f2h.get(w.toLowerCase()) || w.toLowerCase();

let total = 0;
for (const v of freq.values()) total += v;

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const heads = new Set();
let n = 0;
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const txt = fs.readFileSync(path.join(W, t + '.yml'), 'utf8');
  let m; const re = new RegExp(WORD_RE.source, 'gm');
  while ((m = re.exec(txt)) !== null) { n++; heads.add(headOf(m[1])); }
}
let cov = 0;
for (const h of heads) if (freq.has(h)) cov += freq.get(h);

console.log('词条总数      :', n);
console.log('不同 NGSL 词元:', heads.size);
console.log('覆盖率        :', (cov / total * 100).toFixed(2) + '%');
