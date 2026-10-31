const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');
const ECDICT = 'C:/Users/ADMINI~1/AppData/Local/Temp/ecdict.csv';
const TATOEBA = 'C:/Users/ADMINI~1/AppData/Local/Temp/eng_sentences.tsv';

// ---------- NGSL ----------
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

// ---------- ECDICT：每个词的【全部】词性+义项 ----------
function parseCsv(line) {
  const out = []; let cur = ''; let inQ = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inQ) {
      if (c === '"') { if (line[i + 1] === '"') { cur += '"'; i++; } else inQ = false; }
      else cur += c;
    } else if (c === '"') inQ = true;
    else if (c === ',') { out.push(cur); cur = ''; }
    else cur += c;
  }
  out.push(cur);
  return out;
}

const missing = JSON.parse(fs.readFileSync(path.join(VOCAB, 'ecdict-missing.json'), 'utf8'));
const wanted = new Set(missing.map(e => e.w.toLowerCase()));

// POS 缩写 -> 词库词性
const POS_MAP = [
  ['n', 'noun'], ['vt', 'verb'], ['vi', 'verb'], ['v', 'verb'], ['aux', 'verb'],
  ['adj', 'adjective'], ['a', 'adjective'], ['adv', 'adverb'],
  ['prep', 'preposition'], ['pron', 'pronoun'], ['conj', 'conjunction'],
  ['art', 'determiner'], ['num', 'numeral'], ['int', 'interjection'], ['interj', 'interjection'],
];
function mapPos(abbr) {
  for (const [k, v] of POS_MAP) if (k === abbr) return v;
  return null;
}

// 把 translation 拆成 {pos, glosses[]} 列表
function parseSenses(translation) {
  if (!translation) return [];
  const senses = [];
  // ECDICT 用字面量 \n 分隔不同词性块
  for (const block of translation.split('\\n')) {
    const b = block.trim();
    if (!b) continue;
    const m = b.match(/^([a-z]+)\.\s*(.*)$/);
    if (!m) continue;
    const pos = mapPos(m[1]);
    if (!pos) continue;
    // 义项用 , ， ; ； 分隔
    const glosses = m[2].split(/[,，;；]/).map(x => x.trim()).filter(Boolean);
    if (glosses.length) senses.push({ pos, glosses, abbr: m[1] });
  }
  return senses;
}

const ecSenses = new Map();
for (const line of fs.readFileSync(ECDICT, 'utf8').split('\n')) {
  if (!line) continue;
  const f = parseCsv(line);
  const w = (f[0] || '').toLowerCase();
  if (!wanted.has(w)) continue;
  const senses = parseSenses(f[3] || '');
  if (senses.length) ecSenses.set(w, senses);
}
console.log('ECDICT 多词性表:', ecSenses.size, '/', wanted.size);

// ---------- 例句库：按（词, 形态）索引 ----------
const byWord = new Map();   // headword -> [sentence...]
const allSents = new Map(); // sentence -> true (去重用)
const sentSet = fs.readFileSync(TATOEBA, 'utf8').split('\n');
function isProse(s) {
  if (!s || s.length > 95) return false;
  const n = s.split(/\s+/).length;
  if (n < 5 || n > 11) return false;
  // 排除问句结尾（词库现有例句全部是陈述句，保持风格一致）
  if (/\?\s*$/.test(s)) return false;
  // 排除带引号/冒号/破折号的长句（这些通常是书面语或对话片段）
  if (/["“”]/.test(s)) return false;
  // 排除明显的专有名词/学术味词汇（Tatoeba 里混进了不少）
  if (/\b(Hopi|onomatopoetic|theoretically|professor|linen|violence|believer)\b/i.test(s)) return false;
  // 排除"目标词只是固定搭配的一部分"的常见坑：as to / come by / do well to ...
  // （这类由下面的搭配黑名单逐词处理）
  return true;
}

// 固定搭配黑名单：当目标词以这些搭配出现时，句子的词性判定会失真，直接跳过该句
const COLLOC_SKIP = {
  as: [/\bas to\b/i, /\bas well as\b/i, /\bas for\b/i],
  by: [/\bcome by\b/i, /\bby the way\b/i],
  well: [/\bdo well to\b/i, /\bas well\b/i, /\bwell worth\b/i],
  since: [/\blong since\b/i],
  so: [/\bso as to\b/i, /\bor so\b/i],
  use: [/\bno use\b/i, /\bmake use of\b/i],
  account: [/\btake .* into account\b/i, /\bon account of\b/i],
  way: [/\bby the way\b/i, /\bin a way\b/i, /\bon .* way\b/i],
  end: [/\bin the end\b/i, /\bend up\b/i],
  mean: [/\bmean to\b/i],
  right: [/\bright away\b/i, /\ball right\b/i],
  point: [/\bpoint of view\b/i],
  turn: [/\bin turn\b/i, /\bturn out\b/i],
  kind: [/\bkind of\b/i],
  matter: [/\bno matter\b/i],
  case: [/\bin case\b/i, /\bjust in case\b/i],
};
function skipColloc(word, sentence) {
  const list = COLLOC_SKIP[word.toLowerCase()];
  if (!list) return false;
  return list.some(re => re.test(sentence));
}
for (const line of sentSet) {
  const i1 = line.indexOf('\t'); if (i1 < 0) continue;
  const i2 = line.indexOf('\t', i1 + 1); if (i2 < 0) continue;
  const s = line.slice(i2 + 1).trim();
  if (!isProse(s)) continue;
  allSents.set(s, true);
  const toks = s.toLowerCase().replace(/[^a-z' ]/g, ' ').split(/\s+/).filter(Boolean);
  const heads = new Set(toks.map(headOf));
  for (const h of heads) {
    if (!wanted.has(h)) continue;
    if (!byWord.has(h)) byWord.set(h, []);
    const arr = byWord.get(h);
    if (arr.length < 400) arr.push(s);
  }
}
console.log('例句索引:', byWord.size, '个目标词各有候选');

// ---------- 词性判定启发式 ----------
// 英语里，词性主要看它在句中的位置。
//   determiner/adjective/numeral: 后面紧跟名词
//   verb: 前面是主语/情态/助动词，或前面是 to
//   noun: 前面是冠词/形容词/介词
//   adverb: 修饰动词/形容词，常在句末
const AUX = new Set(['am','is','are','was','were','be','been','being','do','does','did',
  'have','has','had','will','would','shall','should','can','could','may','might','must','to',
  'not','never','also','just']);
const DET = new Set(['a','an','the','this','that','these','those','my','your','his','her',
  'its','our','their','some','any','no','every','each']);
const PREP = new Set(['of','in','on','at','by','for','with','from','about','into','over',
  'under','after','before','between','through','during','against','without','within','to',
  'upon','above','below','near','around','behind','across','along','among','beside']);

function guessPos(tokens, idx) {
  const w = tokens[idx];
  const prev = idx > 0 ? tokens[idx - 1] : '';
  const next = idx + 1 < tokens.length ? tokens[idx + 1] : '';
  const prev2 = idx > 1 ? tokens[idx - 2] : '';

  const isNounish = t => /^[a-z]+(s|es|ing|ed)?$/.test(t) && !DET.has(t) && !PREP.has(t);
  const nextIsDet = DET.has(next);
  const nextIsPre = PREP.has(next);
  const prevIsDet = DET.has(prev);
  const prevIsPre = PREP.has(prev);
  const prevIsAux = AUX.has(prev);
  const prev2IsDet = DET.has(prev2);

  // 以 -ly 结尾 → 副词
  if (/ly$/.test(w)) return 'adverb';
  // 后面直接跟冠词/限定词 → 动词（"use a bed"）或介词（"into the room"）
  if (nextIsDet && /(ing|ed)$/.test(w)) return 'verb';
  // 前面是助动词/情态/to → 动词
  if (prevIsAux) return 'verb';
  // 前面是限定词 → 名词或形容词
  if (prevIsDet) {
    if (nextIsDet) return 'adjective';        // "the other book"
    if (next && isNounish(next)) return 'adjective';  // "the other thing"
    return 'noun';
  }
  // 前面是介词 → 名词（介词宾语）
  if (prevIsPre) {
    if (next && isNounish(next) && PREP.has(w)) return 'preposition';
    return 'noun';
  }
  // 句首且后面跟限定词/名词 → 可能是动词（祈使句）或副词
  if (idx === 0) {
    if (nextIsDet || (next && isNounish(next))) return 'verb';
    return 'adverb';
  }
  return null;  // 判不出
}

// ---------- 为每个词挑 (词性, 义项, 例句) 三元组 ----------
const usedSentences = new Set();
const results = [];
let aligned = 0, guessed = 0, fellBack = 0;

for (const e of missing) {
  const w = e.w.toLowerCase();
  const senses = ecSenses.get(w) || [];
  const cands = byWord.get(headOf(w)) || byWord.get(w) || [];

  // 对每个候选句，推断目标词在该句中的词性
  const scored = [];
  for (const s of cands) {
    if (usedSentences.has(s)) continue;
    if (skipColloc(w, s)) continue;        // 固定搭配会让词性判定失真，跳过
    const toks = s.toLowerCase().replace(/[^a-z' ]/g, ' ').split(/\s+/).filter(Boolean);
    const targetHead = headOf(w);
    for (let i = 0; i < toks.length; i++) {
      if (headOf(toks[i]) !== targetHead && toks[i] !== w) continue;
      let p = guessPos(toks, i);
      let how = 'syntax';
      if (!p) {
        // 判不出时，若该词只有一个词性就用它
        const ps = new Set(senses.map(x => x.pos));
        if (ps.size === 1) { p = [...ps][0]; how = 'single-pos'; }
        else { p = senses.length ? senses[0].pos : null; how = 'fallback'; }
      }
      if (!p) continue;
      // 该词性必须在 ECDICT 里存在
      const sense = senses.find(x => x.pos === p);
      if (!sense) continue;
      const nTok = toks.length;
      scored.push({ sentence: s, pos: p, sense, how, tokens: nTok, quality: Math.abs(nTok - 7) });
      break;   // 每个句子只算一次
    }
  }

  scored.sort((a, b) => a.quality - b.quality);
  const pick = scored.find(x => x.how === 'syntax')
    || scored.find(x => x.how === 'single-pos')
    || scored[0];

  if (!pick) {
    fellBack++;
    results.push({ ...e, aligned: false, note: 'no example with matching POS' });
    continue;
  }
  if (pick.how === 'syntax') aligned++;
  else if (pick.how === 'single-pos') guessed++;
  else fellBack++;

  usedSentences.add(pick.sentence);
  // 取该词性下的第一个义项（最常用）
  const gloss = pick.sense.glosses[0];
  results.push({
    ...e,
    pos: pick.pos,
    chinese: gloss,
    example_final: pick.sentence,
    pos_source: pick.how,
    aligned: pick.how !== 'fallback'
  });
}

console.log(`\n句法对齐成功: ${aligned}`);
console.log(`单词性词直接对齐: ${guessed}`);
console.log(`无法对齐(回退): ${fellBack}`);

const bad = results.filter(r => !r.aligned);
if (bad.length) {
  console.log('\n需人工处理:');
  bad.slice(0, 30).forEach(r => console.log(`  ${r.w.padEnd(12)} [${r.pos}] ${r.example_final || '(none)'}`));
}

fs.writeFileSync(path.join(VOCAB, 'aligned.json'), JSON.stringify(results, null, 2));
console.log('\nwrote aligned.json');
