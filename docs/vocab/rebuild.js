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
const POS_RE = /^\s{4}part_of_speech:\s*"((?:[^"\\]|\\.)*)"/;
const LVL_RE = /^\s{4}difficulty_level:\s*(\d+)/;
function readBank(tier) {
  const out = []; let cur = null;
  for (const l of fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8').split(/\r?\n/)) {
    let m;
    if ((m = l.match(WORD_RE))) { if (cur) out.push(cur); cur = { english: m[1], pos: null, level: 1, tier }; continue; }
    if (!cur) continue;
    if ((m = l.match(POS_RE))) cur.pos = m[1];
    if ((m = l.match(LVL_RE))) cur.level = +m[1];
  }
  if (cur) out.push(cur);
  return out;
}
const all = [];
for (const t of ['beginner', 'intermediate', 'advanced']) all.push(...readBank(t));

// ---- 保护规则 ----
// B 方案：按 NGSL 排名重排，但"基础具体词"不降档。
// 判定"基础"的三条依据（任一命中即保护）：
//   1. 词性是可数/具体的名词（noun）且难度等级 <= 3
//   2. 该词已有 difficulty_level <= 3（原作者标为简单）
//   3. 该词在 CEFR-J A1/A2 名单内（如果有）
// 这里先用 1+2 两条（CEFR-J 数据本次未下载，留作后续增强）。
const CONCRETE_HINT = /^(noun|adjective|numeral|determiner|pronoun|preposition|conjunction)$/;

function isProtected(w) {
  // 现有 difficulty_level <= 3 视为作者已判定为"简单词"，不降档
  if (w.level <= 3) return true;
  return false;
}

const TIERS = ['beginner', 'intermediate', 'advanced'];
const ORD = { beginner: 0, intermediate: 1, advanced: 2 };
const B1 = 800, B2 = 1500;   // 比 500/1000 更宽：beginner 覆盖到 800 名，减少基础词误伤

function targetTier(r) {
  if (r === 0) return null;
  if (r <= B1) return 'beginner';
  if (r <= B2) return 'intermediate';
  return 'advanced';
}

const moves = [];
let protectedCount = 0;
for (const w of all) {
  const r = rankOf(w.english);
  if (r === 0) continue;                    // 无排名：保持原档
  const tgt = targetTier(r);
  if (tgt === w.tier) continue;             // 不动
  if (ORD[tgt] > ORD[w.tier] && isProtected(w)) { protectedCount++; continue; }  // 降档被保护
  moves.push({ english: w.english, from: w.tier, to: tgt, rank: r, level: w.level });
}

console.log(`=== B 方案重排 (边界 ${B1}/${B2}) ===`);
console.log(`总词数            : ${all.length}`);
console.log(`档位不变          : ${all.length - moves.length - protectedCount}`);
console.log(`受保护未降档      : ${protectedCount}`);
console.log(`实际移动          : ${moves.length}`);
const down = moves.filter(m => ORD[m.to] > ORD[m.from]);
const up = moves.filter(m => ORD[m.to] < ORD[m.from]);
console.log(`  降档(变难)      : ${down.length}`);
console.log(`  升档(变简单)    : ${up.length}`);

console.log('\n降档示例（前 15，检查是否仍是基础词）:');
console.log('  ' + down.slice(0, 15).map(m => `${m.english}(${m.rank})`).join('  '));
console.log('\n升档示例（前 15）:');
console.log('  ' + up.slice(0, 15).map(m => `${m.english}(${m.rank})`).join('  '));

fs.writeFileSync(path.join(VOCAB, 'rebuild-final.json'), JSON.stringify({
  boundaries: { beginnerMax: B1, intermediateMax: B2 },
  protectedCount,
  moves
}, null, 2));
console.log('\nwrote rebuild-final.json');
