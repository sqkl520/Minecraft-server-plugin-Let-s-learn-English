const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');

const statsRows = fs.readFileSync(path.join(VOCAB, 'ngsl_stats.csv'), 'utf8')
  .split(/\r?\n/).filter(Boolean).slice(1);
const rank = new Map(), freq = new Map();
for (const line of statsRows) {
  const m = line.match(/^"?(.*?)"?,(\d+),([\d.]+),(\d+)$/);
  if (!m) continue;
  rank.set(m[1].toLowerCase(), +m[2]);
  freq.set(m[1].toLowerCase(), +m[4]);
}

const formToHead = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_lemma.csv'), 'utf8')
  .split(/\r?\n/).filter(l => l && !l.startsWith('#'))) {
  const p = line.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (!p.length) continue;
  const h = p[0];
  for (const f of p) if (!formToHead.has(f)) formToHead.set(f, h);
}
const headOf = w => formToHead.get(w.toLowerCase()) || w.toLowerCase();
const rankOf = w => { const h = headOf(w); return rank.has(h) ? rank.get(h) : 0; };

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const FIELD_RE = /^\s{4}([a-z_]+):\s*(.*)$/;

function readBank(tier) {
  const t = fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8');
  const out = []; let cur = null;
  for (const l of t.split(/\r?\n/)) {
    let m;
    if ((m = l.match(WORD_RE))) {
      if (cur) out.push(cur);
      cur = { english: m[1].replace(/\\"/g, '"'), fields: {} };
      continue;
    }
    if (!cur) continue;
    if ((m = l.match(FIELD_RE))) cur.fields[m[1]] = m[2].trim();
  }
  if (cur) out.push(cur);
  return out;
}

const banks = {};
for (const t of ['beginner', 'intermediate', 'advanced']) banks[t] = readBank(t);
const existing = new Set();
for (const t in banks) for (const e of banks[t]) existing.add(e.english.toLowerCase());

let total = 0;
for (const v of freq.values()) total += v;
const curHeads = new Set();
for (const t in banks) for (const e of banks[t]) curHeads.add(headOf(e.english));
let curCov = 0;
for (const h of curHeads) if (freq.has(h)) curCov += freq.get(h);

const missingTop500 = [...rank.entries()]
  .filter(([w, r]) => r <= 500 && !existing.has(w))
  .sort((a, b) => a[1] - b[1]);
let addCov = 0;
for (const [w] of missingTop500) addCov += freq.get(w);

console.log('=== SCOPE ===');
console.log(`current            : ${Object.values(banks).reduce((s, b) => s + b.length, 0)} words, coverage ${(curCov / total * 100).toFixed(2)}%`);
console.log(`+ top-500 missing  : +${missingTop500.length} words, coverage ${((curCov + addCov) / total * 100).toFixed(2)}%`);

const ORD = { beginner: 0, intermediate: 1, advanced: 2 };
console.log('\n=== BOUNDARY OPTIONS ============');
for (const [b1, b2, label] of [[500, 1000, '500/1000'], [600, 1200, '600/1200'], [800, 1500, '800/1500'], [1000, 2000, '1000/2000']]) {
  const tFor = r => r === 0 ? null : (r <= b1 ? 'beginner' : (r <= b2 ? 'intermediate' : 'advanced'));
  let up = 0, down = 0, stay = 0, un = 0;
  for (const t in banks) for (const e of banks[t]) {
    const r = rankOf(e.english);
    if (!r) { un++; continue; }
    const tg = tFor(r);
    if (tg === t) stay++;
    else if (ORD[tg] < ORD[t]) up++; else down++;
  }
  const res = { beginner: 0, intermediate: 0, advanced: 0 };
  for (const t in banks) for (const e of banks[t]) res[tFor(rankOf(e.english)) || t]++;
  for (const [w, r] of missingTop500) res[tFor(r) || 'beginner']++;
  console.log(`${label.padEnd(10)} stay=${String(stay).padStart(3)} unranked=${String(un).padStart(3)} easier=${String(up).padStart(3)} harder=${String(down).padStart(3)}  ->  b=${res.beginner} i=${res.intermediate} a=${res.advanced}`);
}

// Detail for the recommended boundary
const REC = [500, 1000, '500/1000'];
const tFor = r => r === 0 ? null : (r <= REC[0] ? 'beginner' : (r <= REC[1] ? 'intermediate' : 'advanced'));
const downList = [];
for (const t in banks) for (const e of banks[t]) {
  const r = rankOf(e.english);
  if (!r) continue;
  const tg = tFor(r);
  if (tg && ORD[tg] > ORD[t]) downList.push({ w: e.english, from: t, to: tg, r });
}
downList.sort((a, b) => a.r - b.r);
console.log(`\n=== ${REC[2]}: words demoted to a HARDER tier (${downList.length}) ===`);
console.log(downList.slice(0, 25).map(x => `${x.w}(${x.r})`).join(' '));

const upList = [];
for (const t in banks) for (const e of banks[t]) {
  const r = rankOf(e.english);
  if (!r) continue;
  const tg = tFor(r);
  if (tg && ORD[tg] < ORD[t]) upList.push({ w: e.english, from: t, to: tg, r });
}
upList.sort((a, b) => a.r - b.r);
console.log(`\n=== ${REC[2]}: words promoted to an EASIER tier (${upList.length}) ===`);
console.log(upList.slice(0, 25).map(x => `${x.w}(${x.r})`).join(' '));

fs.writeFileSync(path.join(VOCAB, 'rebuild-plan.json'), JSON.stringify({
  boundaries: { beginnerMax: REC[0], intermediateMax: REC[1] },
  coverageNow: +(curCov / total * 100).toFixed(2),
  coverageAfter: +((curCov + addCov) / total * 100).toFixed(2),
  toAdd: missingTop500.map(([w, r]) => ({ w, r, freq: freq.get(w) })),
  demote: downList, promote: upList
}, null, 2));
console.log('\nwrote rebuild-plan.json');
