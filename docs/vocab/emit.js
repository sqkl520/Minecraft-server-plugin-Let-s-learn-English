const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');

const aligned = JSON.parse(fs.readFileSync(path.join(VOCAB, 'aligned.json'), 'utf8'));
const plan = JSON.parse(fs.readFileSync(path.join(VOCAB, 'rebuild-final.json'), 'utf8'));

// ---------- 1. 新词分档：按排名三等分 ----------
// 332 个词的排名范围是 22..500。按排名切成三段，让三档新增量接近，
// 同时保证"排名越靠前 -> 档位越简单"这个单调性。
const sorted = [...aligned].sort((a, b) => a.rank - b.rank);
const n = sorted.length;
const cut1 = Math.floor(n / 3);
const cut2 = Math.floor(n * 2 / 3);
for (let i = 0; i < n; i++) {
  sorted[i].tier = i < cut1 ? 'beginner' : (i < cut2 ? 'intermediate' : 'advanced');
}
const tiers = { beginner: 0, intermediate: 0, advanced: 0 };
for (const e of sorted) tiers[e.tier]++;
console.log('新词分档:', JSON.stringify(tiers));
console.log(`  排名范围 beginner: ${sorted[0].rank}..${sorted[cut1 - 1].rank}`);
console.log(`  排名范围 intermediate: ${sorted[cut1].rank}..${sorted[cut2 - 1].rank}`);
console.log(`  排名范围 advanced: ${sorted[cut2].rank}..${sorted[n - 1].rank}`);

// ---------- 2. 读取现有词库，应用重排 ----------
// 【必须从 git HEAD 读】不能从工作区读：工作区里的 words/*.yml 可能已经是
// 上一次生成的产物，再读一遍会把 332 个新词当成"老词"重复追加（实测踩过两次，
// 词数从 1357 变成 1689）。HEAD 是重建前的原始 1025 词，是唯一正确的基准。
const { execSync } = require('child_process');
const WORD_RE_SRC = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
function readBankRaw(tier) {
  const txt = execSync('git show HEAD:src/main/resources/words/' + tier + '.yml',
    { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 });
  const lines = txt.split(/\r?\n/);
  const header = [];
  const blocks = [];
  let cur = null;
  let inWords = false;
  for (const l of lines) {
    if (/^words:\s*$/.test(l)) { inWords = true; header.push(l); continue; }
    if (!inWords) { header.push(l); continue; }
    if (/^\s{2}-\s+english:/.test(l)) {
      if (cur) blocks.push(cur);
      cur = [l];
    } else if (cur) {
      cur.push(l);
    }
  }
  if (cur) blocks.push(cur);
  return { header, blocks };
}
const WORD_RE = WORD_RE_SRC;

// 老词的 freq_rank 补全：原 1025 词没有这个字段，导致档内排序退化成
// 「新词按频率在前 + 老词按字母序在后」两截 —— 这不是重排，是拼接。
// 凡是能映射到 NGSL 词元的老词，一律补上排名；映射不到的（apple/banana/pencil
// 这类具象名词）保持无排名，排在档尾按字母序，符合"不在通用频次表内"的语义。
const ngslRank = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_stats.csv'), 'utf8')
  .split(/\r?\n/).filter(Boolean).slice(1)) {
  const m = line.match(/^"?(.*?)"?,(\d+),([\d.]+),(\d+)$/);
  if (m) ngslRank.set(m[1].toLowerCase(), +m[2]);
}
const formToHead = new Map();
for (const line of fs.readFileSync(path.join(VOCAB, 'ngsl_lemma.csv'), 'utf8')
  .split(/\r?\n/).filter(l => l && !l.startsWith('#'))) {
  const p = line.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  if (p.length) for (const f of p) if (!formToHead.has(f)) formToHead.set(f, p[0]);
}
function ngslRankOf(word) {
  const lw = word.toLowerCase();
  const head = formToHead.get(lw) || lw;
  return ngslRank.has(head) ? ngslRank.get(head) : 0;
}

/** 给一个老词块补 freq_rank（若它还没有该字段且有 NGSL 排名）。 */
function injectFreqRank(block, word) {
  if (block.some(l => /^\s{4}freq_rank:/.test(l))) return block;   // 已有，不动
  const r = ngslRankOf(word);
  if (!r) return block;                                            // 无排名，保持原样
  const out = block.slice();
  // 插在 difficulty_level 之后（若没有则追加到末尾），保持字段顺序可读
  const idx = out.findIndex(l => /^\s{4}difficulty_level:/.test(l));
  if (idx >= 0) out.splice(idx + 1, 0, `    freq_rank: ${r}`);
  else out.push(`    freq_rank: ${r}`);
  return out;
}
function readBankRawOld(tier) {
  const txt = fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8');
  const lines = txt.split(/\r?\n/);
  const header = [];
  const blocks = [];
  let cur = null;
  let inWords = false;
  for (const l of lines) {
    if (/^words:\s*$/.test(l)) { inWords = true; header.push(l); continue; }
    if (!inWords) { header.push(l); continue; }
    if (/^\s{2}-\s+english:/.test(l)) {
      if (cur) blocks.push(cur);
      cur = [l];
    } else if (cur) {
      cur.push(l);
    }
  }
  if (cur) blocks.push(cur);
  return { header, blocks };
}

function blockWord(block) {
  const m = block[0].match(WORD_RE);
  return m ? m[1] : null;
}

// 原词库里的既有错配修正（`WordBankContentTest` 上线后抓出来的）。
// 这些词**在原始 1025 词库里**（不在 332 新词里），所以不走 align.js；
// 直接在生成阶段替换整块。键 = 小写词形，值 = 新的完整 8 字段块（行数组）。
// 注意：新词的订正走 manual-fix.js，两者分工不同 —— 若把新词写进这里，
// 它会因为"既被 align 生成一次、又被这里替换一次"而在输出里出现两份。
const LEGACY_FIXES = {
  bag: [
    '  - english: "bag"',
    '    chinese: "包；袋子"',
    '    part_of_speech: "noun"',
    '    phonetic: "/bæɡ/"',
    '    example_sentence: "She put the books in her bag."',
    '    synonyms: []',
    '    antonyms: []',
    '    difficulty_level: 1',
    '    freq_rank: 1252',
  ],
  // and / his 是原库老词，例句含 Tatoeba 占位人名（Tom/Jim、Tom），换成无人名的自然例句。
  // 它们是**老词**，所以不能写进 manual-fix.js（那只覆盖 332 新词）。
  and: [
    '  - english: "and"',
    '    chinese: "和；并且"',
    '    part_of_speech: "conjunction"',
    '    phonetic: "/ænd/"',
    '    example_sentence: "She bought bread and milk."',
    '    synonyms: []',
    '    antonyms: []',
    '    difficulty_level: 1',
    '    freq_rank: 3',
  ],
  his: [
    '  - english: "his"',
    '    chinese: "他的"',
    '    part_of_speech: "determiner"',
    '    phonetic: "/hɪz/"',
    '    example_sentence: "He put his book on the desk."',
    '    synonyms: []',
    '    antonyms: []',
    '    difficulty_level: 1',
    '    freq_rank: 11',
  ],
};

const data = {};
for (const t of ['beginner', 'intermediate', 'advanced']) data[t] = readBankRaw(t);

const moveMap = new Map();
for (const m of plan.moves) moveMap.set(m.english.toLowerCase(), m.to);

// 按目标档重新分桶
const buckets = { beginner: [], intermediate: [], advanced: [] };
for (const t of ['beginner', 'intermediate', 'advanced']) {
  for (const b of data[t].blocks) {
    const w = blockWord(b);
    if (!w) continue;
    const to = moveMap.get(w.toLowerCase()) || t;
    // 原库既有错配：整块替换（见 LEGACY_FIXES 注释）
    const fix = LEGACY_FIXES[w.toLowerCase()];
    // 老词补 freq_rank（新词的块里已自带，injectFreqRank 会跳过）
    const blk = injectFreqRank(fix ? fix.slice() : b, w);
    buckets[to].push({ english: w, block: blk });
  }
}
for (const t in buckets) console.log(`重排后 ${t}: ${buckets[t].length}`);

// ---------- 3. 生成新词 YAML 块 ----------
function yamlEsc(s) { return String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"'); }
function levelFor(rank) {
  if (rank <= 100) return 1;
  if (rank <= 200) return 2;
  if (rank <= 300) return 3;
  if (rank <= 400) return 4;
  return 5;
}
function newBlock(e) {
  return [
    `  - english: "${yamlEsc(e.w)}"`,
    `    chinese: "${yamlEsc(e.chinese)}"`,
    `    part_of_speech: "${e.pos}"`,
    `    phonetic: "${e.ipa}"`,
    `    example_sentence: "${yamlEsc(e.example_final)}"`,
    `    synonyms: []`,
    `    antonyms: []`,
    `    difficulty_level: ${levelFor(e.rank)}`,
    `    freq_rank: ${e.rank}`,
  ];
}

for (const e of sorted) buckets[e.tier].push({ english: e.w, block: newBlock(e), isNew: true });

// ---------- 4. 每档内按 freq_rank 排序（无排名的排最后，保持原序）----------
function rankOfBlock(b) {
  // 有 freq_rank 字段就用；新词块已带
  for (const l of (b.block || [])) if (/freq_rank:\s*(\d+)/.test(l)) return +l.match(/freq_rank:\s*(\d+)/)[1];
  return null;
}
for (const t in buckets) {
  buckets[t].sort((a, b) => {
    const ra = rankOfBlock(a), rb = rankOfBlock(b);
    if (ra === null && rb === null) return a.english.localeCompare(b.english);
    if (ra === null) return 1;
    if (rb === null) return -1;
    return ra - rb;
  });
}

// ---------- 5. 写出到 docs/vocab/generated/（不直接改词库）----------
const outDir = path.join(VOCAB, 'generated');
fs.mkdirSync(outDir, { recursive: true });
const DIFF = { beginner: 'Beginner', intermediate: 'Intermediate', advanced: 'Advanced' };
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const hdr = [
    '# ============================================================',
    `#  ${DIFF[t]} Word Bank`,
    `#  Rebuilt ${new Date().toISOString().slice(0, 10)}: ordered by NGSL 1.2 frequency rank`,
    `#  ${buckets[t].length} words (${buckets[t].filter(x => x.isNew).length} newly added)`,
    '# ============================================================',
    '',
    `difficulty: "${t}"`,
    `description: "${DIFF[t]} English Word Bank"`,
    '',
    'words:',
  ];
  const body = buckets[t].map(x => (x.block || []).join('\n')).join('\n\n');
  fs.writeFileSync(path.join(outDir, `${t}.yml`), hdr.join('\n') + '\n' + body + '\n');
}

console.log('\n写出 generated/{beginner,intermediate,advanced}.yml');
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const nWords = buckets[t].length;
  const nNew = buckets[t].filter(x => x.isNew).length;
  console.log(`  ${t}.yml: ${nWords} 词（新增 ${nNew}）`);
}
