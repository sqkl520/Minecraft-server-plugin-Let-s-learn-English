const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');
const TATOEBA = 'C:/Users/ADMINI~1/AppData/Local/Temp/eng_sentences.tsv';

const rank = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_stats.csv'), 'utf8')
  .split(/\r?\n/).filter(Boolean).slice(1)) {
  const m = line.match(/^"?(.*?)"?,(\d+),([\d.]+),(\d+)$/);
  if (m) rank.set(m[1].toLowerCase(), +m[2]);
}
const f2h = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_lemma.csv'), 'utf8')
  .split(/\r?\n/).filter(l => l && !l.startsWith('#'))) {
  const p = line.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (p.length) for (const f of p) if (!f2h.has(f)) f2h.set(f, p[0]);
}
const headOf = w => f2h.get(w.toLowerCase()) || w.toLowerCase();
const rankOf = w => { const h = headOf(w); return rank.has(h) ? rank.get(h) : 0; };

// 现有词库词汇（作为"玩家已学过的词"的白名单，用于 i+1 判据）
const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const knownWords = new Set();
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const txt = fs.readFileSync(path.join(WORDS, t + '.yml'), 'utf8');
  let m; const re = new RegExp(WORD_RE.source, 'gm');
  while ((m = re.exec(txt)) !== null) knownWords.add(m[1].toLowerCase());
}

const missing = JSON.parse(fs.readFileSync(path.join(VOCAB, 'ecdict-missing.json'), 'utf8'));
const wanted = new Map();
for (const e of missing) wanted.set(e.w.toLowerCase(), e);

// 常见功能词白名单：这些不算"生词"，即使不在词库也不计入超纲
const FUNCTION_WORDS = new Set(('a an the i you he she it we they me him her us them my your his its our their '
  + 'this that these those am is are was were be been being have has had do does did will would shall should '
  + 'can could may might must not no yes and or but if then than so as at by for with from to of in on into '
  + 'over under about after before between during through up down out off again just very too also there here '
  + 'what who which when where why how all any some each every both few many much more most other another such '
  + 'own same only because while until since though although well now still even ever never always often '
  + 'sometimes usually today tomorrow yesterday please thanks thank ok okay').split(/\s+/));

function tokens(sentence) {
  return sentence.toLowerCase().replace(/[^a-z' ]/g, ' ').split(/\s+/).filter(Boolean);
}

// 评估一句例句的质量
function score(sentence, target) {
  const toks = tokens(sentence);
  if (toks.length < 4 || toks.length > 12) return null;

  // 必须真的含目标词（按词形还原匹配，允许复数/时态）
  const targetHead = headOf(target);
  const hasTarget = toks.some(t => headOf(t) === targetHead || t === target.toLowerCase());
  if (!hasTarget) return null;

  // i+1：统计超出"现有词库 + 功能词"的实词个数
  const unknown = [];
  for (const t of toks) {
    if (FUNCTION_WORDS.has(t)) continue;
    if (knownWords.has(t)) continue;
    if (headOf(t) === targetHead) continue;          // 目标词本身不算生词
    if (rankOf(t) > 0 && rankOf(t) <= 500) continue;  // 高频词也算已会
    unknown.push(t);
  }

  // 目标：0-1 个生词最佳
  if (unknown.length > 2) return null;
  return {
    sentence: sentence.trim(),
    tokens: toks.length,
    unknown: unknown.length,
    unknownWords: unknown,
    score: unknown.length * 100 + Math.abs(toks.length - 7)   // 生词越少、长度越接近 7 越好
  };
}

// 流式扫描 Tatoeba，为每个目标词保留最好的 3 个候选
const best = new Map();
const lines = fs.readFileSync(TATOEBA, 'utf8').split('\n');
console.log('tatoeba lines:', lines.length);

let scanned = 0;
for (const line of lines) {
  const tab = line.indexOf('\t');
  if (tab < 0) continue;
  const tab2 = line.indexOf('\t', tab + 1);
  if (tab2 < 0) continue;
  const sent = line.slice(tab2 + 1);
  if (!sent || sent.length > 120) continue;
  scanned++;

  const toks = tokens(sent);
  for (const t of toks) {
    const head = headOf(t);
    const target = wanted.has(head) ? head : (wanted.has(t) ? t : null);
    if (!target) continue;
    const s = score(sent, target);
    if (!s) continue;
    const arr = best.get(target) || [];
    if (arr.length < 3) { arr.push(s); arr.sort((a, b) => a.score - b.score); }
    else if (s.score < arr[arr.length - 1].score) {
      arr[arr.length - 1] = s; arr.sort((a, b) => a.score - b.score);
    }
    best.set(target, arr);
  }
}
console.log('scanned sentences:', scanned);
console.log('words with candidates:', best.size, '/', wanted.size);

const noCand = [...wanted.keys()].filter(w => !best.has(w));
console.log('no candidates:', noCand.length);
if (noCand.length) console.log('  ', noCand.join(' '));

console.log('\n=== SAMPLE ===');
let n = 0;
for (const [w, arr] of best) {
  if (n++ >= 12) break;
  const b = arr[0];
  console.log(`${w.padEnd(12)} [${b.tokens}w, ${b.unknown} unknown] ${b.sentence}`);
}

// 写回
for (const e of missing) {
  const arr = best.get(e.w.toLowerCase());
  if (arr && arr.length) {
    e.example_candidate = arr[0];
    e.example_alternatives = arr.slice(1).map(x => x.sentence);
  }
}
fs.writeFileSync(path.join(VOCAB, 'ecdict-missing.json'), JSON.stringify(missing, null, 2));
console.log('\nupdated ecdict-missing.json with example candidates');
