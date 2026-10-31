const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');

const statsRows = fs.readFileSync(path.join(VOCAB, 'ngsl_stats.csv'), 'utf8')
  .split(/\r?\n/).filter(Boolean).slice(1);
const rank = new Map();
for (const line of statsRows) {
  const m = line.match(/^"?(.*?)"?,(\d+),([\d.]+),(\d+)$/);
  if (!m) continue;
  rank.set(m[1].toLowerCase(), +m[2]);
}
const formToHead = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_lemma.csv'), 'utf8')
  .split(/\r?\n/).filter(l => l && !l.startsWith('#'))) {
  const p = line.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (!p.length) continue;
  for (const f of p) if (!formToHead.has(f)) formToHead.set(f, p[0]);
}
const headOf = w => formToHead.get(w.toLowerCase()) || w.toLowerCase();
const rankOf = w => { const h = headOf(w); return rank.has(h) ? rank.get(h) : 0; };

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
function words(tier) {
  const t = fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8');
  const out = []; let m;
  const re = new RegExp(WORD_RE.source, 'gm');
  while ((m = re.exec(t)) !== null) out.push(m[1].replace(/\\"/g, '"'));
  return out;
}
function pct(arr, p) { return arr[Math.min(arr.length - 1, Math.floor(arr.length * p))]; }

console.log('=== RANK DISTRIBUTION PER CURRENT TIER ===');
for (const tier of ['beginner', 'intermediate', 'advanced']) {
  const ws = words(tier);
  const rs = ws.map(rankOf).filter(r => r > 0).sort((a, b) => a - b);
  console.log(`${tier.padEnd(13)} n=${ws.length} matched=${rs.length}`);
  console.log(`   rank  p10=${pct(rs, 0.1)}  p25=${pct(rs, 0.25)}  p50=${pct(rs, 0.5)}  p75=${pct(rs, 0.75)}  p90=${pct(rs, 0.9)}  max=${rs[rs.length - 1]}`);
}

// What boundary would preserve beginner best? Find beginner's p90/p95.
const bws = words('beginner').map(rankOf).filter(r => r > 0).sort((a, b) => a - b);
console.log('\nbeginner p90 =', pct(bws, 0.9), ' p95 =', pct(bws, 0.95));

// Simulate: how many words churn for each candidate beginner boundary, counting ONLY
// words that currently sit in beginner (we care about not demoting basic words)
console.log('\n=== churn among CURRENT BEGINNER words for each boundary ===');
for (const b of [500, 600, 700, 800, 900, 1000, 1200]) {
  const demoted = bws.filter(r => r > b);
  console.log(`  beginnerMax=${String(b).padStart(4)}  demotes ${String(demoted.length).padStart(3)} of ${bws.length} beginner words  (${(demoted.length / bws.length * 100).toFixed(0)}%)`);
}

console.log('\n=== churn among CURRENT ADVANCED words ===');
const aws = words('advanced').map(rankOf).filter(r => r > 0);
for (const [b2, label] of [[1000, '1000'], [1500, '1500'], [2000, '2000']]) {
  const promoted = aws.filter(r => r <= b2);
  console.log(`  intermediateMax=${label.padStart(4)}  promotes ${promoted.length} of ${aws.length} advanced words up`);
}
