const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');
const ECDICT = 'C:/Users/ADMINI~1/AppData/Local/Temp/ecdict.csv';

// --- NGSL ---
const rank = new Map(), freq = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_stats.csv'), 'utf8')
  .split(/\r?\n/).filter(Boolean).slice(1)) {
  const m = line.match(/^"?(.*?)"?,(\d+),([\d.]+),(\d+)$/);
  if (m) { rank.set(m[1].toLowerCase(), +m[2]); freq.set(m[1].toLowerCase(), +m[4]); }
}
const formToHead = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_lemma.csv'), 'utf8')
  .split(/\r?\n/).filter(l => l && !l.startsWith('#'))) {
  const p = line.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (p.length) for (const f of p) if (!formToHead.has(f)) formToHead.set(f, p[0]);
}

// --- existing words ---
const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/gm;
const existing = new Set();
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const txt = fs.readFileSync(path.join(WORDS, t + '.yml'), 'utf8');
  let m; const re = new RegExp(WORD_RE.source, 'gm');
  while ((m = re.exec(txt)) !== null) existing.add(m[1].toLowerCase());
}

const missing = [...rank.entries()].filter(([w, r]) => r <= 500 && !existing.has(w))
  .sort((a, b) => a[1] - b[1]);

// --- ECDICT lookup (streaming, file is 66 MB) ---
// parse CSV rows handling quoted fields with embedded commas/newlines("\\n" literal)
function parseCsvLine(line) {
  const out = []; let cur = ''; let inQ = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inQ) {
      if (c === '"') { if (line[i + 1] === '"') { cur += '"'; i++; } else inQ = false; }
      else cur += c;
    } else {
      if (c === '"') inQ = true;
      else if (c === ',') { out.push(cur); cur = ''; }
      else cur += c;
    }
  }
  out.push(cur);
  return out;
}

const want = new Set(missing.map(x => x[0]));
const found = new Map();

const data = fs.readFileSync(ECDICT, 'utf8');
const lines = data.split('\n');
console.log('ecdict lines:', lines.length);
for (let i = 1; i < lines.length; i++) {
  const line = lines[i];
  if (!line) continue;
  const f = parseCsvLine(line);
  const w = (f[0] || '').toLowerCase();
  if (!w || !want.has(w)) continue;
  found.set(w, {
    word: f[0], phonetic: f[1], translation: f[3], pos: f[4],
    collins: f[5], oxford: f[6], tag: f[7], bnc: f[8], frq: f[9], exchange: f[10]
  });
  if (found.size === want.size) break;
}

console.log(`wanted ${want.size}, found in ECDICT ${found.size}, missing ${want.size - found.size}`);
const notFound = [...want].filter(w => !found.has(w));
if (notFound.length) console.log('NOT FOUND:', notFound.join(' '));

// sample 12
console.log('\n=== SAMPLE ===');
let n = 0;
for (const [w, r] of missing) {
  const e = found.get(w);
  if (!e || n++ >= 12) continue;
  const tr = (e.translation || '').split('\\n')[0].slice(0, 60);
  console.log(`${w.padEnd(14)} r=${String(r).padStart(3)}  ${(e.phonetic || '-').padEnd(16)} ${(e.pos || '-').padEnd(12)} ${tr}`);
}

// quality check: how many have phonetic / translation / pos
let okPh = 0, okTr = 0, okPos = 0, okEx = 0;
for (const [w] of missing) {
  const e = found.get(w); if (!e) continue;
  if (e.phonetic && e.phonetic.trim()) okPh++;
  if (e.translation && e.translation.trim()) okTr++;
  if (e.pos && e.pos.trim()) okPos++;
  if (e.exchange && e.exchange.trim()) okEx++;
}
console.log('\n=== COVERAGE OF ECDICT FIELDS ===');
console.log(`phonetic   ${okPh}/${missing.length}`);
console.log(`translation ${okTr}/${missing.length}`);
console.log(`pos        ${okPos}/${missing.length}`);
console.log(`exchange   ${okEx}/${missing.length}`);

fs.writeFileSync(path.join(VOCAB, 'ecdict-missing.json'),
  JSON.stringify(missing.map(([w, r]) => ({ w, rank: r, freq: freq.get(w), ...(found.get(w) || {}) })), null, 2));
console.log('\nwrote ecdict-missing.json');
