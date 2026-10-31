const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');

const missing = JSON.parse(fs.readFileSync(path.join(VOCAB, 'ecdict-missing.json'), 'utf8'));
const plan = JSON.parse(fs.readFileSync(path.join(VOCAB, 'rebuild-final.json'), 'utf8'));

// ---------- 1. 去重例句：给重复的词换备选 ----------
const used = new Set();
// 先按"词越冷门越优先保留原句"的顺序不重要；直接贪心：重复就换下一条备选
for (const e of missing) {
  if (!e.example_candidate) continue;
  const s = e.example_candidate.sentence;
  if (!used.has(s)) { used.add(s); continue; }
  // 换备选
  let replaced = false;
  for (const alt of (e.example_alternatives || [])) {
    if (!used.has(alt)) { e.example_candidate.sentence = alt; used.add(alt); replaced = true; break; }
  }
  if (!replaced) {
    // 三个备选都被占了：保留原句但标记冲突，供人工处理
    e.example_duplicate = true;
  }
}
const stillDup = missing.filter(e => e.example_duplicate).map(e => e.w);
console.log('去重后仍冲突:', stillDup.length, stillDup.join(' '));

// ---------- 2. 校验新词四字段齐全 ----------
const incomplete = missing.filter(e => !e.chinese || !e.pos || !e.ipa || !e.example_candidate);
console.log('字段不全:', incomplete.length, incomplete.map(e => e.w).join(' '));

// ---------- 3. 生成 YAML 条目 ----------
function yamlEscape(s) {
  return String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}
function levelFor(rank) {
  // 与现有词库难度曲线对齐：按排名给 1..9
  if (rank <= 100) return 1;
  if (rank <= 200) return 2;
  if (rank <= 300) return 3;
  if (rank <= 400) return 4;
  return 5;
}
function entryYaml(e, tier) {
  const lvl = levelFor(e.rank);
  const lines = [
    `  - english: "${yamlEscape(e.w)}"`,
    `    chinese: "${yamlEscape(e.chinese)}"`,
    `    part_of_speech: "${e.pos}"`,
    `    phonetic: "${e.ipa}"`,
    `    example_sentence: "${yamlEscape(e.example_candidate.sentence)}"`,
    `    synonyms: []`,
    `    antonyms: []`,
    `    difficulty_level: ${lvl}`,
    `    freq_rank: ${e.rank}`,
  ];
  return lines.join('\n');
}

// 分配档位：排名 <=800 -> beginner, <=1500 -> intermediate, 其余 advanced
function tierForRank(r) {
  if (r <= 800) return 'beginner';
  if (r <= 1500) return 'intermediate';
  return 'advanced';
}
const byTier = { beginner: [], intermediate: [], advanced: [] };
for (const e of missing) byTier[tierForRank(e.rank)].push(e);

console.log('\n新增词分档:');
for (const t in byTier) console.log(`  ${t}: ${byTier[t].length}`);

// ---------- 4. 写产物（先写单独文件，不直接改原词库）----------
const outDir = path.join(VOCAB, 'generated');
fs.mkdirSync(outDir, { recursive: true });
for (const t in byTier) {
  const body = byTier[t].map(e => entryYaml(e, t)).join('\n\n');
  fs.writeFileSync(path.join(outDir, `new-${t}.yml`), body + '\n');
}
console.log('\nwrote generated/new-*.yml');

// 预览
console.log('\n=== 生成样例 ===');
console.log(entryYaml(byTier.beginner[0], 'beginner'));
