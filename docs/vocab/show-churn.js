const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');

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

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
function words(tier) {
  const txt = fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8');
  const out = []; let m;
  const re = new RegExp(WORD_RE.source, 'gm');
  while ((m = re.exec(txt)) !== null) out.push(m[1]);
  return out;
}

console.log('如果严格按 NGSL 排名切档（前500=beginner / 501-1000=intermediate / 1001+=advanced）：\n');
const b = words('beginner');
const demoted = b.filter(w => rankOf(w) > 500);
console.log(`现在属于 beginner 的 ${b.length} 个词里，有 ${demoted.length} 个会被降级。`);
console.log('\n前 20 个被降级的（括号内是 NGSL 排名）：');
console.log('  ' + demoted.slice(0, 20).map(w => `${w}(${rankOf(w)})`).join('  '));

console.log('\n意思就是：一个刚入门的玩家，本来在 beginner 档学的这些词，');
console.log('改版后突然跑到更难的两档去了 —— 而这些恰恰是最基础的词。');
