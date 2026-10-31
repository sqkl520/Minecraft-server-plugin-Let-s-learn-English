const fs = require('fs'), path = require('path');
const VOCAB = __dirname;

// ECDICT 的 pos 字段为空，但 translation 每行以词性缩写开头（n. / v. / adj. / adv. ...）
// 从释义里稳定地抽取词性，再映射成词库用的英文全称。
const POS_MAP = [
  [/^n\./, 'noun'],
  // 情态助动词：ECDICT 写 aux.，词库现有 1025 词里出现的是 verb（如 can/must/will）。
  // 词库的 part_of_speech 只取 10 个英文全称，没有 auxiliary，归到 verb 保持词表封闭。
  [/^aux\./, 'verb'],
  [/^vt\.|^vi\.|^v\./, 'verb'],
  [/^adj\.|^a\./, 'adjective'],
  [/^adv\./, 'adverb'],
  [/^prep\./, 'preposition'],
  [/^pron\./, 'pronoun'],
  [/^conj\./, 'conjunction'],
  [/^art\./, 'determiner'],
  [/^num\./, 'numeral'],
  [/^int\.|^interj\./, 'interjection'],
];

function extractPos(translation) {
  if (!translation) return null;
  // translation 形如 "prep. 被, 经, 由, 在...之旁\\nn. ..."；取第一行首个词性标记
  const first = translation.split('\\n')[0].trim();
  for (const [re, pos] of POS_MAP) if (re.test(first)) return pos;
  return null;
}

// 中文释义清洗：去掉词性前缀、去掉 "\\n" 后的其余义项、压缩空白
function cleanChinese(translation) {
  if (!translation) return null;
  const first = translation.split('\\n')[0].trim();
  let s = first.replace(/^(n|vt|vi|v|adj|a|adv|prep|pron|conj|art|num|int|interj)\.\s*/, '');
  s = s.replace(/\s+/g, ' ').trim();
  // 只保留前两个义项，用 ； 连接（与现有词库 "关于；大约" 的风格一致）
  const parts = s.split(/[,，;；]/).map(x => x.trim()).filter(Boolean).slice(0, 2);
  return parts.join('；') || null;
}

const missing = JSON.parse(fs.readFileSync(path.join(VOCAB, 'ecdict-missing.json'), 'utf8'));
let posOk = 0, cnOk = 0;
const noPos = [], noCn = [];
for (const e of missing) {
  e.pos = extractPos(e.translation);
  e.chinese = cleanChinese(e.translation);
  if (e.pos) posOk++; else noPos.push(e.w);
  if (e.chinese) cnOk++; else noCn.push(e.w);
}
console.log(`pos 抽取成功 ${posOk}/${missing.length}`);
if (noPos.length) console.log('  失败:', noPos.join(' '));
console.log(`中文释义生成 ${cnOk}/${missing.length}`);
if (noCn.length) console.log('  失败:', noCn.join(' '));

console.log('\n=== SAMPLE ===');
for (const e of missing.slice(0, 20)) {
  console.log(`${e.w.padEnd(12)} r=${String(e.rank).padStart(3)} ${String(e.pos || '-').padEnd(12)} ${e.chinese || '-'}`);
}
fs.writeFileSync(path.join(VOCAB, 'ecdict-missing.json'), JSON.stringify(missing, null, 2));
console.log('\nupdated');
