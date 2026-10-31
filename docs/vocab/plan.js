const fs = require('fs'), path = require('path');

const ROOT = 'D:/Server/MCServers/Server plugin/Let\'s learn English!/Minecraft-server-plugin-Let-s-learn-English--1';
const VOCAB = path.join(ROOT, 'docs', 'vocab');
const WORDS = path.join(ROOT, 'src', 'main', 'resources', 'words');
const ECDICT = 'C:/Users/ADMINI~1/AppData/Local/Temp/ecdict.csv';

// ---------- 1. NGSL ----------
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

// ---------- 2. current word banks ----------
function readBank(tier) {
  const t = fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8');
  const lines = t.split(/\r?\n/);
  const entries = [];
  let cur = null;
  for (const raw of lines) {
    const l = raw;
    let m;
    if ((m = l.match(/^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/))) {
      if (cur) entries.push(cur);
      cur = { english: unesc(m[1]), fields: {} };
      continue;
    }
    if (!cur) continue;
    if ((m = l.match(/^\s{4}([a-z_]+):\s*(.*)$/))) {
      const k = m[1];
      let v = m[2].trim();
      cur.fields[k] = v;
    }
  }
  if (cur) entries.push(cur);
  return entries;
}
function unesc(s) {
  // YAML double-quoted: handle \\ and \" only (the word banks use plain ASCII words)
  return s.replace(/\\"/g, '"').replace(/\\\\/g, '\\');
}
function yamlStr(s) {
  return '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

const banks = {};
for (const tier of ['beginner', 'intermediate', 'advanced']) banks[tier] = readBank(tier);
for (const tier of Object.keys(banks)) {
  console.log(`${tier}: ${banks[tier].length} entries parsed`);
}
const sample = banks.advanced[0];
console.log('sample fields:', Object.keys(sample.fields).join(', '));
console.log('sample entry:', JSON.stringify(sample).slice(0, 200));

// ---------- 3. NGSL assignment ----------
function headOf(w) {
  const lw = w.toLowerCase();
  return formToHead.get(lw) || lw;
}
function rankOf(w) {
  const h = headOf(w);
  return rank.has(h) ? rank.get(h) : 0;
}

// proposed tier boundaries
const B1 = 1000;   // 1..1000 -> beginner
const B2 = 2000;   // 1001..2000 -> intermediate
                   // 2001+ -> advanced
function tierFor(r) {
  if (r === 0) return null;      // no NGSL rank -> keep existing tier
  if (r <= B1) return 'beginner';
  if (r <= B2) return 'intermediate';
  return 'advanced';
}

const moves = { up: [], down: [], stay: 0, unranked: 0 };
const existing = new Set();
for (const tier of Object.keys(banks)) {
  for (const e of banks[tier]) {
    const w = e.english;
    existing.add(w.toLowerCase());
    const r = rankOf(w);
    if (r === 0) { moves.unranked++; continue; }
    const target = tierFor(r);
    if (target === tier) { moves.stay++; }
    else {
      const order = { beginner: 0, intermediate: 1, advanced: 2 };
      if (order[target] < order[tier]) moves.up.push({ w, from: tier, to: target, r });
      else moves.down.push({ w, from: tier, to: target, r });
    }
  }
}
console.log('\n=== TIER MOVES (by NGSL rank) ===');
console.log('stay in tier      :', moves.stay);
console.log('no NGSL rank      :', moves.unranked, '(keep current tier)');
console.log('move to easier tier:', moves.up.length);
console.log('move to harder tier:', moves.down.length);

// ---------- 4. words to ADD (missing from NGSL top 2000) ----------
const toAdd = [];
for (const [w, r] of [...rank.entries()].sort((a, b) => a[1] - b[1])) {
  if (r > 2000) break;
  if (existing.has(w)) continue;
  toAdd.push({ w, r, freq: freq.get(w) });
}
console.log('\n=== WORDS TO ADD (NGSL top-2000 missing) ===');
console.log('count:', toAdd.length);
console.log('first 30:', toAdd.slice(0, 30).map(x => x.w + '(' + x.r + ')').join(' '));

// coverage impact
let total = 0; for (const v of freq.values()) total += v;
let curCov = 0;
const curHeads = new Set();
for (const tier of Object.keys(banks)) for (const e of banks[tier]) curHeads.add(headOf(e.english));
for (const h of curHeads) if (freq.has(h)) curCov += freq.get(h);
let addCov = 0;
for (const a of toAdd) addCov += a.freq;
console.log('\ncoverage now      :', (curCov / total * 100).toFixed(2) + '%');
console.log('coverage after add:', ((curCov + addCov) / total * 100).toFixed(2) + '%');

// ---------- 5. write plan ----------
fs.writeFileSync(path.join(VOCAB, 'rebuild-plan.json'), JSON.stringify({
  boundaries: { beginner: [1, B1], intermediate: [B1 + 1, B2], advanced: [B2 + 1, null] },
  stats: {
    stayInTier: moves.stay,
    noNgslRank: moves.unranked,
    moveEasier: moves.up.length,
    moveHarder: moves.down.length,
    toAdd: toAdd.length
  },
  moveEasier: moves.up,
  moveHarder: moves.down,
  toAdd
}, null, 2));
console.log('\nwrote rebuild-plan.json');
