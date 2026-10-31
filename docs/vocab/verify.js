const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const GEN = path.join(VOCAB, 'generated');

// 模拟 WordBank 的加载器：按 yml 结构解析，检查每个条目 8 字段是否齐全、
// 例句是否含目标词、freq_rank 是否唯一、音标格式是否与现有词库一致。
const FIELDS = ['english', 'chinese', 'part_of_speech', 'phonetic', 'example_sentence',
  'synonyms', 'antonyms', 'difficulty_level'];

function parseBank(file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const entries = []; let cur = null; let inWords = false;
  for (const l of lines) {
    if (/^words:\s*$/.test(l)) { inWords = true; continue; }
    if (!inWords) continue;
    const m = l.match(/^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/);
    if (m) { if (cur) entries.push(cur); cur = { english: m[1], fields: { english: m[1] } }; continue; }
    if (!cur) continue;
    const f = l.match(/^\s{4}([a-z_]+):\s*(.*)$/);
    if (f) cur.fields[f[1]] = f[2].trim();
  }
  if (cur) entries.push(cur);
  return entries;
}

// 词库现有 1025 词只用这 10 个词性，新词必须保持一致（封闭词表）
const POS_ALLOWED = new Set(['noun', 'verb', 'adjective', 'adverb', 'preposition',
  'pronoun', 'conjunction', 'determiner', 'numeral', 'interjection']);

// 不规则动词/名词变化表（只列这批词里实际出现的），让"例句含目标词"的检查
// 不会把 meant/told/left/kept 这类正确用法误报为问题。
const IRREGULAR = {
  mean: ['meant'], tell: ['told'], keep: ['kept'], bring: ['brought'], hold: ['held'],
  lead: ['led'], spend: ['spent'], send: ['sent'], commit: ['committed'], forbid: ['forbidden'],
  hug: ['hugged'], cut: ['cut'], compel: ['compelled'], cram: ['crammed'], dispel: ['dispelled'],
  drag: ['dragged'], grin: ['grinned'], bad: ['worse', 'worst'], leave: ['left'],
  program: ['programming', 'programs'], activity: ['activities'], feel: ['felt'],
  get: ['got', 'gotten'], find: ['found'], say: ['said'], think: ['thought'],
  come: ['came'], go: ['went'], see: ['saw', 'seen'], take: ['took', 'taken'],
  give: ['gave', 'given'], make: ['made'], know: ['knew', 'known'], become: ['became'],
  begin: ['began', 'begun'], run: ['ran'], write: ['wrote', 'written'], speak: ['spoke', 'spoken'],
  break: ['broke', 'broken'], choose: ['chose', 'chosen'], drive: ['drove', 'driven'],
  eat: ['ate', 'eaten'], fall: ['fell', 'fallen'], forget: ['forgot', 'forgotten'],
  hear: ['heard'], lose: ['lost'], meet: ['met'], pay: ['paid'], put: ['put'],
  read: ['read'], rise: ['rose', 'risen'], sell: ['sold'], sit: ['sat'], sleep: ['slept'],
  stand: ['stood'], teach: ['taught'], understand: ['understood'], wear: ['wore', 'worn'],
  win: ['won'], child: ['children'], person: ['people'], man: ['men'], woman: ['women'],
  foot: ['feet'], tooth: ['teeth'], mouse: ['mice'], life: ['lives'], wife: ['wives'],
};

function containsWord(w, sentence) {
  const lw = w.toLowerCase();
  const stem = lw.replace(/e$/, '');
  const forms = new Set([lw, stem + 's', stem + 'es', stem + 'ed', stem + 'd', stem + 'ing',
    lw + 's', lw + 'ed', lw + 'ing']);
  for (const f of (IRREGULAR[lw] || [])) forms.add(f);
  return [...forms].some(f => new RegExp('\\b' + f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\b', 'i').test(sentence));
}

let problems = [];
const allRanks = new Map();
for (const tier of ['beginner', 'intermediate', 'advanced']) {
  const entries = parseBank(path.join(GEN, tier + '.yml'));
  console.log(`${tier}: ${entries.length} entries`);
  for (const e of entries) {
    const f = e.fields;
    const w = e.english;
    // 1. 字段齐全
    for (const k of FIELDS) if (!(k in f)) problems.push(`${tier}/${w}: 缺字段 ${k}`);
    // 2. 例句含目标词（含不规则变化，见 containsWord）
    const ex = (f.example_sentence || '').replace(/^"|"$/g, '');
    if (ex && !containsWord(w, ex)) problems.push(`${tier}/${w}: 例句不含目标词 -> "${ex}"`);
    // 3. 音标格式 /.../
    const ph = (f.phonetic || '').replace(/^"|"$/g, '');
    if (ph && !/^\/.+\/$/.test(ph)) problems.push(`${tier}/${w}: 音标格式异常 ${ph}`);
    // 4. 词性在允许集
    const pos = (f.part_of_speech || '').replace(/^"|"$/g, '');
    if (pos && !POS_ALLOWED.has(pos)) problems.push(`${tier}/${w}: 词性非法 ${pos}`);
    // 5. freq_rank 不重复
    const fr = f.freq_rank;
    if (fr !== undefined && fr !== '') {
      const v = parseInt(fr, 10);
      if (!isNaN(v)) {
        if (allRanks.has(v)) problems.push(`${tier}/${w}: freq_rank ${v} 与 ${allRanks.get(v)} 重复`);
        else allRanks.set(v, `${tier}/${w}`);
      }
    }
  }
}

console.log(`\n=== 校验结果 ===`);
console.log(`问题数: ${problems.length}`);
if (problems.length) {
  console.log(problems.slice(0, 30).join('\n'));
  if (problems.length > 30) console.log(`... 还有 ${problems.length - 30} 条`);
} else {
  console.log('全部通过');
}
