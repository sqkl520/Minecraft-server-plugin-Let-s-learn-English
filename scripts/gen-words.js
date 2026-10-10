#!/usr/bin/env node
'use strict';
/*
 * gen-words.js — Word-bank sample generator + self-check (Phase 0 infra, stream D).
 *
 * Zero runtime deps (Node core only). NOT packaged into the plugin JAR:
 * it lives in <root>/scripts/, outside src/main/resources, so Maven never
 * copies it into target/classes.
 *
 * Usage (run from anywhere; paths resolve relative to this file):
 *   node scripts/gen-words.js            # generate sample-beginner.yml + self-check
 *   node scripts/gen-words.js generate   # same as above
 *   node scripts/gen-words.js check FILE # validate an existing word-bank YAML
 *
 * Validation mirrors the plugin's WordBankValidator.java (english/chinese
 * required & non-empty; part_of_speech/phonetic/example_sentence strings if
 * present; synonyms/antonyms string lists; difficulty_level & reward numbers)
 * and adds template-completeness + quality checks. '# REVIEW:' notes mark
 * values pending manual verification (存疑); they are warnings, never errors.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const WORDS_DIR = path.join(ROOT, 'src', 'main', 'resources', 'words');
const SAMPLE_OUT = path.join(WORDS_DIR, 'sample-beginner.yml');
const BEGINNER_REF = path.join(WORDS_DIR, 'beginner.yml');

const POS_SET = new Set(['noun', 'verb', 'adjective', 'adverb', 'pronoun',
  'preposition', 'conjunction', 'interjection', 'determiner', 'numeral']);

function w(english, chinese, part_of_speech, phonetic, example_sentence, synonyms, antonyms, difficulty_level, review) {
  return { english, chinese, part_of_speech, phonetic, example_sentence, synonyms, antonyms, difficulty_level, review: review || {} };
}

// Canonical 50-word beginner sample. Excludes the 10 words already in beginner.yml
// (apple, book, cat, dog, eat, friend, happy, run, school, water).
const WORDS = [
  w('arm', '手臂', 'noun', '/ɑːm/', 'She hurt her arm while playing.', ['limb'], [], 1),
  w('ball', '球', 'noun', '/bɔːl/', 'The children are playing with a ball.', [], [], 1),
  w('banana', '香蕉', 'noun', '/bəˈnɑː.nə/', 'A monkey is eating a banana.', [], [], 1, { phonetic: 'UK /bəˈnɑː.nə/ vs US /bəˈnæn.ə/ — confirm against project dictionary' }),
  w('bed', '床', 'noun', '/bed/', "I go to bed at ten o'clock.", [], [], 1),
  w('big', '大的', 'adjective', '/bɪɡ/', 'They live in a big house.', ['large', 'huge'], ['small', 'little'], 1),
  w('bird', '鸟', 'noun', '/bɜːd/', 'A bird is singing in the tree.', [], [], 1),
  w('blue', '蓝色的', 'adjective', '/bluː/', 'The sky is blue today.', [], [], 1),
  w('boy', '男孩', 'noun', '/bɔɪ/', 'The boy is riding a bicycle.', ['lad'], ['girl'], 1),
  w('bread', '面包', 'noun', '/bred/', 'I like bread with butter.', ['loaf'], [], 1),
  w('car', '汽车', 'noun', '/kɑːr/', 'My father drives a red car.', ['automobile', 'vehicle'], [], 1, { phonetic: 'rhotic-r convention: UK /kɑː/ ~ /kɑːr/ (file keeps the r, cf. water)' }),
  w('chair', '椅子', 'noun', '/tʃeər/', 'Please sit on the chair.', ['seat'], [], 1),
  w('cold', '冷的', 'adjective', '/kəʊld/', 'The water is very cold.', ['chilly', 'cool'], ['hot', 'warm'], 1),
  w('come', '来', 'verb', '/kʌm/', 'Please come to my party.', ['approach', 'arrive'], ['go', 'leave'], 1),
  w('day', '天；白天', 'noun', '/deɪ/', 'It is a beautiful day.', [], ['night'], 1),
  w('door', '门', 'noun', '/dɔːr/', 'Please close the door.', [], [], 1),
  w('drink', '喝', 'verb', '/drɪŋk/', 'I drink milk every morning.', ['sip'], [], 1),
  w('egg', '鸡蛋', 'noun', '/eɡ/', 'She eats an egg for breakfast.', [], [], 1),
  w('family', '家庭', 'noun', '/ˈfæm.əl.i/', 'My family is very big.', ['household', 'relatives'], [], 2, { phonetic: 'syllabification varies: /ˈfæm.əl.i/ (3 syl) ~ /ˈfæm.li/ (2 syl)' }),
  w('fast', '快的', 'adjective', '/fɑːst/', 'He is a fast runner.', ['quick', 'rapid'], ['slow'], 1),
  w('fire', '火', 'noun', '/faɪər/', 'They sat around the warm fire.', ['flame'], [], 1),
  w('fish', '鱼', 'noun', '/fɪʃ/', 'There are many fish in the river.', [], [], 1),
  w('flower', '花', 'noun', '/ˈflaʊ.ər/', 'She picked a beautiful flower.', ['blossom'], [], 1),
  w('food', '食物', 'noun', '/fuːd/', 'The food smells delicious.', ['meal'], [], 1),
  w('girl', '女孩', 'noun', '/ɡɜːl/', 'The girl is reading a story.', [], ['boy'], 1),
  w('good', '好的', 'adjective', '/ɡʊd/', 'This is a good book.', ['fine', 'nice'], ['bad'], 1),
  w('green', '绿色的', 'adjective', '/ɡriːn/', 'The grass is green.', [], [], 1),
  w('hand', '手', 'noun', '/hænd/', 'Raise your hand to answer.', [], [], 1),
  w('help', '帮助', 'verb', '/help/', 'Can you help me, please?', ['assist', 'aid'], [], 1),
  w('home', '家', 'noun', '/həʊm/', 'I go home after school.', ['house'], [], 1),
  w('hot', '热的', 'adjective', '/hɒt/', 'The soup is too hot.', ['warm'], ['cold'], 1),
  w('house', '房子', 'noun', '/haʊs/', 'They bought a new house.', ['home'], [], 1),
  w('know', '知道', 'verb', '/nəʊ/', 'I know the right answer.', ['understand'], [], 2),
  w('learn', '学习', 'verb', '/lɜːn/', 'We learn English at school.', ['study'], [], 1),
  w('like', '喜欢', 'verb', '/laɪk/', 'I like ice cream very much.', ['enjoy'], ['dislike', 'hate'], 1),
  w('little', '小的', 'adjective', '/ˈlɪt.əl/', 'She has a little dog.', ['small', 'tiny'], ['big', 'large'], 1),
  w('look', '看', 'verb', '/lʊk/', 'Look at the beautiful bird!', ['watch'], [], 1),
  w('love', '爱', 'verb', '/lʌv/', 'I love my family very much.', ['adore'], ['hate'], 1),
  w('milk', '牛奶', 'noun', '/mɪlk/', 'The baby is drinking milk.', [], [], 1),
  w('money', '钱', 'noun', '/ˈmʌn.i/', 'He saves his money in a bank.', ['cash'], [], 2),
  w('moon', '月亮', 'noun', '/muːn/', 'The moon is bright tonight.', [], ['sun'], 1),
  w('mother', '母亲', 'noun', '/ˈmʌð.ər/', 'My mother cooks dinner for us.', ['mom', 'mum'], ['father'], 1),
  w('new', '新的', 'adjective', '/njuː/', 'I have a new phone.', ['fresh', 'modern'], ['old'], 1),
  w('night', '夜晚', 'noun', '/naɪt/', 'The stars shine at night.', [], ['day'], 1),
  w('open', '打开', 'verb', '/ˈəʊ.pən/', 'Please open the window.', ['unlock'], ['close', 'shut'], 2),
  w('orange', '橙子；橙色的', 'noun', '/ˈɒr.ɪndʒ/', 'I drink orange juice every day.', [], [], 1, { phonetic: 'UK /ˈɒr.ɪndʒ/ vs US /ˈɔːr.ɪndʒ/' }),
  w('play', '玩；演奏', 'verb', '/pleɪ/', 'The children play in the garden.', [], [], 1),
  w('read', '读', 'verb', '/riːd/', 'I read a book before bed.', [], [], 1),
  w('red', '红色的', 'adjective', '/red/', 'She is wearing a red dress.', [], [], 1),
  w('sing', '唱歌', 'verb', '/sɪŋ/', 'The birds sing in the morning.', [], [], 1),
  w('sleep', '睡觉', 'verb', '/sliːp/', 'Babies sleep for many hours.', ['rest', 'nap'], ['wake'], 1),
];

// ---------- YAML emitter (mirrors beginner.yml byte-style) ----------
function esc(s) { return String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"'); }
function q(s) { return '"' + esc(s) + '"'; }

function emitList(key, arr, indent) {
  const pad = ' '.repeat(indent);
  if (!arr || arr.length === 0) return pad + key + ': []';
  const lines = [pad + key + ':'];
  for (const item of arr) lines.push(' '.repeat(indent + 2) + '- ' + q(item));
  return lines.join('\n');
}

function emitYaml(bank) {
  const L = [];
  L.push('# ============================================================');
  L.push('#  Beginner Word Bank — REVIEW SAMPLE (NOT an active bank)');
  L.push('#  Auto-generated by scripts/gen-words.js — do not hand-edit.');
  L.push('#  The plugin only auto-loads beginner/intermediate/advanced');
  L.push('#  into the data folder, so this file is inert at runtime.');
  L.push('#  Promote approved entries by merging them into beginner.yml');
  L.push('#  after manual review.');
  L.push("#  '# REVIEW:' marks a value pending human verification (存疑).");
  L.push('# ============================================================');
  L.push('');
  L.push('difficulty: ' + q(bank.difficulty));
  L.push('description: ' + q(bank.description));
  L.push('');
  L.push('words:');
  bank.words.forEach((e, i) => {
    if (i > 0) L.push('');
    const c = (f) => (e.review && e.review[f]) ? '  # REVIEW: ' + e.review[f] : '';
    L.push('  - english: ' + q(e.english) + c('english'));
    L.push('    chinese: ' + q(e.chinese) + c('chinese'));
    L.push('    part_of_speech: ' + q(e.part_of_speech) + c('part_of_speech'));
    L.push('    phonetic: ' + q(e.phonetic) + c('phonetic'));
    L.push('    example_sentence: ' + q(e.example_sentence) + c('example_sentence'));
    L.push(emitList('synonyms', e.synonyms, 4));
    L.push(emitList('antonyms', e.antonyms, 4));
    L.push('    difficulty_level: ' + e.difficulty_level + c('difficulty_level'));
  });
  return L.join('\n') + '\n';
}
// ---------- Focused YAML parser (validates the subset we emit) ----------
function parseScalar(rest) {
  rest = rest.replace(/\s+$/, '');
  const m = rest.match(/^"((?:[^"\\]|\\.)*)"/);
  if (m) {
    const after = rest.slice(m[0].length).trim();
    if (after && !after.startsWith('#')) throw new Error('junk after string: ' + rest);
    return m[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }
  const h = rest.indexOf('#');
  const v = (h >= 0 ? rest.slice(0, h) : rest).trim();
  if (v === '[]') return [];
  if (/^-?\d+$/.test(v)) return parseInt(v, 10);
  if (/^-?\d+\.\d+$/.test(v)) return parseFloat(v);
  throw new Error('unrecognized scalar: ' + JSON.stringify(rest));
}

function parseWordsYaml(text) {
  const lines = text.split(/\r?\n/);
  const indentOf = (s) => s.match(/^ */)[0].length;
  const result = { difficulty: null, description: null, words: [] };
  let i = 0;
  for (; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim() || line.trim().startsWith('#')) continue;
    if (indentOf(line) !== 0) throw new Error('line ' + (i + 1) + ': unexpected indent at top level');
    const m = line.match(/^([A-Za-z_]\w*):\s*(.*)$/);
    if (!m) throw new Error('line ' + (i + 1) + ': expected top-level key: ' + line);
    if (m[1] === 'words') { i++; break; }
    result[m[1]] = parseScalar(m[2]);
  }
  let cur = null, pendingList = null;
  for (; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const ind = indentOf(line);
    const body = line.slice(ind);
    if (ind === 2 && body.startsWith('- ')) {
      pendingList = null; cur = {}; result.words.push(cur);
      const fm = body.slice(2).match(/^([A-Za-z_]\w*):\s*(.*)$/);
      if (!fm) throw new Error('line ' + (i + 1) + ': bad entry start: ' + line);
      cur[fm[1]] = parseScalar(fm[2]);
    } else if (ind === 4) {
      pendingList = null;
      if (!cur) throw new Error('line ' + (i + 1) + ': field outside entry');
      const fm = body.match(/^([A-Za-z_]\w*):\s*(.*)$/);
      if (!fm) throw new Error('line ' + (i + 1) + ': bad field: ' + line);
      if (fm[2].trim() === '') { pendingList = fm[1]; cur[fm[1]] = []; }
      else cur[fm[1]] = parseScalar(fm[2]);
    } else if (ind === 6 && body.startsWith('- ')) {
      if (!cur || !pendingList) throw new Error('line ' + (i + 1) + ': list item without header');
      cur[pendingList].push(parseScalar(body.slice(2)));
    } else {
      throw new Error('line ' + (i + 1) + ': unexpected structure: ' + JSON.stringify(line));
    }
  }
  return result;
}
// ---------- Validator (WordBankValidator rules + template + quality) ----------
function validate(parsed, opts) {
  opts = opts || {};
  const errors = [], warnings = [];
  const seen = new Map();
  if (!Array.isArray(parsed.words)) { errors.push("top-level 'words' is not a list"); return { errors, warnings }; }
  if (parsed.words.length === 0) errors.push("'words' list is empty");
  if (parsed.words.length > 1000) errors.push("'words' exceeds max of 1000 (" + parsed.words.length + ')');
  parsed.words.forEach((e, idx) => {
    const at = 'words[' + idx + ']' + (e && e.english ? " '" + e.english + "'" : '');
    const str = (f, required) => {
      const v = e[f];
      if (v === undefined || v === null) { (required ? errors : warnings).push(at + ': missing ' + f); return; }
      if (typeof v !== 'string') { errors.push(at + ': ' + f + ' must be a string'); return; }
      if (v.trim() === '') (required ? errors : warnings).push(at + ': ' + f + ' is empty');
    };
    str('english', true);
    str('chinese', true);
    str('part_of_speech', false);
    str('phonetic', false);
    str('example_sentence', false);
    for (const lf of ['synonyms', 'antonyms']) {
      const v = e[lf];
      if (v === undefined) { warnings.push(at + ': missing ' + lf); continue; }
      if (!Array.isArray(v)) { errors.push(at + ': ' + lf + ' must be a list'); continue; }
      v.forEach((it, j) => { if (typeof it !== 'string') errors.push(at + '.' + lf + '[' + j + ']: must be a string'); });
    }
    const dl = e.difficulty_level;
    if (dl === undefined) warnings.push(at + ': missing difficulty_level');
    else if (typeof dl !== 'number' || !Number.isInteger(dl)) errors.push(at + ': difficulty_level must be an integer');
    else if (dl < 1) warnings.push(at + ': difficulty_level < 1');
    if (e.reward !== undefined && typeof e.reward !== 'number') errors.push(at + ': reward must be a number');
    if (typeof e.part_of_speech === 'string' && e.part_of_speech && !POS_SET.has(e.part_of_speech))
      warnings.push(at + ': unusual part_of_speech "' + e.part_of_speech + '"');
    if (typeof e.phonetic === 'string' && e.phonetic && !/^\/.*\/$/.test(e.phonetic))
      warnings.push(at + ': phonetic not wrapped in /.../');
    if (typeof e.example_sentence === 'string' && typeof e.english === 'string' && e.example_sentence &&
      !e.example_sentence.toLowerCase().includes(e.english.toLowerCase()))
      warnings.push(at + ': example_sentence does not contain the headword');
    if (typeof e.english === 'string') {
      const k = e.english.toLowerCase();
      if (seen.has(k)) errors.push(at + ': duplicate english (also at words[' + seen.get(k) + '])');
      else seen.set(k, idx);
    }
  });
  if (opts.refWords && opts.refWords.size) parsed.words.forEach((e) => {
    if (typeof e.english === 'string' && opts.refWords.has(e.english.toLowerCase()))
      warnings.push("overlap: '" + e.english + "' already exists in beginner.yml");
  });
  return { errors, warnings };
}
// ---------- helpers ----------
function roundTripCheck(bank, text) {
  const parsed = parseWordsYaml(text);
  const errs = [];
  if (parsed.words.length !== bank.words.length) errs.push('word count mismatch');
  bank.words.forEach((e, i) => {
    const p = parsed.words[i] || {};
    for (const f of ['english', 'chinese', 'part_of_speech', 'phonetic', 'example_sentence', 'difficulty_level'])
      if (JSON.stringify(p[f]) !== JSON.stringify(e[f])) errs.push('[' + i + '] ' + f + ': ' + JSON.stringify(p[f]) + ' != ' + JSON.stringify(e[f]));
    for (const lf of ['synonyms', 'antonyms'])
      if (JSON.stringify(p[lf] || []) !== JSON.stringify(e[lf] || [])) errs.push('[' + i + '] ' + lf + ' mismatch');
  });
  return { parsed, errs };
}

function loadRefWords(file) {
  const set = new Set();
  try {
    const parsed = parseWordsYaml(fs.readFileSync(file, 'utf8'));
    for (const e of parsed.words) if (typeof e.english === 'string') set.add(e.english.toLowerCase());
  } catch (err) { console.warn('  (could not read reference ' + path.basename(file) + ': ' + err.message + ')'); }
  return set;
}

function collectReviews(bank) {
  const out = [];
  bank.words.forEach((e) => { if (e.review) for (const f of Object.keys(e.review)) out.push({ word: e.english, field: f, note: e.review[f] }); });
  return out;
}

function report(label, parsed, vr, reviews) {
  console.log('— ' + label + ' —');
  console.log('  entries: ' + parsed.words.length);
  console.log('  errors : ' + vr.errors.length);
  console.log('  warns  : ' + vr.warnings.length);
  if (reviews) console.log('  review-flagged (存疑): ' + reviews.length);
  vr.errors.forEach((e) => console.log('    [ERROR] ' + e));
  vr.warnings.forEach((x) => console.log('    [warn]  ' + x));
  if (reviews) reviews.forEach((r) => console.log('    [REVIEW] ' + r.word + '.' + r.field + ': ' + r.note));
}
// ---------- commands ----------
function doGenerate() {
  const bank = {
    difficulty: 'beginner',
    description: 'Beginner Word Bank (REVIEW SAMPLE - 50 words, pending manual review)',
    words: WORDS,
  };
  const yaml = emitYaml(bank);
  const { parsed, errs } = roundTripCheck(bank, yaml);
  if (errs.length) { console.error('ROUND-TRIP FAILED (emitter/parser disagree):'); errs.forEach((e) => console.error('  ' + e)); process.exit(2); }
  const refWords = loadRefWords(BEGINNER_REF);
  const vr = validate(parsed, { refWords });
  const reviews = collectReviews(bank);
  fs.mkdirSync(WORDS_DIR, { recursive: true });
  fs.writeFileSync(SAMPLE_OUT, yaml, 'utf8');
  console.log('Wrote ' + SAMPLE_OUT + ' (' + bank.words.length + ' words)');
  console.log('Round-trip (emit -> parse): OK');
  report('self-check (sample-beginner.yml)', parsed, vr, reviews);
  process.exit(vr.errors.length ? 1 : 0);
}

function doCheck(file) {
  const abs = path.resolve(file);
  let parsed;
  try { parsed = parseWordsYaml(fs.readFileSync(abs, 'utf8')); }
  catch (err) { console.error('YAML PARSE ERROR: ' + err.message); process.exit(2); }
  const refWords = abs === path.resolve(BEGINNER_REF) ? new Set() : loadRefWords(BEGINNER_REF);
  const vr = validate(parsed, { refWords });
  report('check ' + path.basename(abs), parsed, vr, null);
  process.exit(vr.errors.length ? 1 : 0);
}

(function main() {
  const [, , cmd, arg] = process.argv;
  if (!cmd || cmd === 'generate') return doGenerate();
  if (cmd === 'check') { if (!arg) { console.error('usage: node scripts/gen-words.js check <file>'); process.exit(2); } return doCheck(arg); }
  console.error('unknown command: ' + cmd + ' (use: generate | check <file>)');
  process.exit(2);
})();
