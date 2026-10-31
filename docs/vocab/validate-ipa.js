const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');
const CMU = 'C:/Users/ADMINI~1/AppData/Local/Temp/cmudict.dict';

// ---- 读现有词库（它们已有公认正确的 IPA，作为金标准）----
const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const PHON_RE = /^\s{4}phonetic:\s*"((?:[^"\\]|\\.)*)"/;
function readBank(tier) {
  const out = []; let cur = null, pending = null;
  for (const l of (fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8').split(/\r?\n/))) {
    let m;
    if ((m = l.match(WORD_RE))) { if (cur) out.push(cur); cur = { english: m[1], phonetic: null }; continue; }
    if (!cur) continue;
    if ((m = l.match(PHON_RE))) cur.phonetic = m[1];
  }
  if (cur) out.push(cur);
  return out;
}
const gold = [];
for (const t of ['beginner', 'intermediate', 'advanced']) gold.push(...readBank(t).filter(e => e.phonetic));

// ---- 转换器（与 ipa.js 相同逻辑）----
const VOWELS = {
  AA: 'ɑː', AE: 'æ', AH0: 'ə', AH1: 'ʌ', AO: 'ɔː', AW: 'aʊ', AY: 'aɪ',
  EH: 'e', ER0: 'ə', ER1: 'ɜː', ER2: 'ə', EY: 'eɪ', IH: 'ɪ', IY: 'iː', OW: 'əʊ',
  OY: 'ɔɪ', UH: 'ʊ', UW: 'uː'
};
const CONS = {
  B: 'b', CH: 'tʃ', D: 'd', DH: 'ð', F: 'f', G: 'ɡ', HH: 'h', JH: 'dʒ',
  K: 'k', L: 'l', M: 'm', N: 'n', NG: 'ŋ', P: 'p', R: 'r', S: 's', SH: 'ʃ',
  T: 't', TH: 'θ', V: 'v', W: 'w', Y: 'j', Z: 'z', ZH: 'ʒ'
};
function conv(line) {
  const parts = line.trim().split(/\s+/).slice(1);
  let out = ''; const marks = [];
  for (const p of parts) {
    const m = p.match(/^([A-Z]+)([0-2])?$/); if (!m) continue;
    const b = m[1], s = m[2];
    if (s !== undefined) {
      const ipa = VOWELS[b + s] || VOWELS[b];
      if (ipa) { marks.push({ pos: out.length, stress: +s }); out += ipa; }
    } else { const ipa = CONS[b]; if (ipa) out += ipa; }
  }
  if (!out) return null;
  if (marks.length <= 1) return '/' + out + '/';
  const pr = marks.find(x => x.stress === 1); if (!pr) return '/' + out + '/';
  let ss = 0; for (const mk of marks) { if (mk.pos >= pr.pos) break; ss = mk.pos; }
  out = ss > 0 ? out.slice(0, ss) + 'ˈ' + out.slice(ss) : 'ˈ' + out;
  return '/' + out + '/';
}

const cmu = new Map();
for (const l of fs.readFileSync(CMU, 'utf8').split('\n')) {
  if (!l || l.startsWith(';;;')) continue;
  const w = l.split(/\s+/)[0].toLowerCase();
  if (!/^[a-z']+$/.test(w)) continue;
  if (!cmu.has(w)) cmu.set(w, l);
}

// ---- 比对：只比"元音/辅音序列"，忽略重音与次要符号，因为重音位置是最易错的 ----
const norm = s => s.replace(/[\/ˈˌ.\s]/g, '');
let exact = 0, loose = 0, miss = 0, diffs = [];
for (const g of gold) {
  const line = cmu.get(g.english.toLowerCase());
  if (!line) { miss++; continue; }
  const ours = conv(line); if (!ours) { miss++; continue; }
  if (ours === g.phonetic) exact++;
  if (norm(ours) === norm(g.phonetic)) loose++;
  else diffs.push({ w: g.english, bank: g.phonetic, ours });
}
const n = gold.length;
console.log(`金标准词数: ${n}`);
console.log(`CMUdict 缺失: ${miss}`);
console.log(`逐字节完全一致: ${exact} (${(exact / n * 100).toFixed(1)}%)`);
console.log(`忽略重音符号后一致: ${loose} (${(loose / n * 100).toFixed(1)}%)`);
console.log(`\n不一致示例（前 25）:`);
console.log(diffs.slice(0, 25).map(d => `  ${d.w.padEnd(14)} bank=${(d.bank || '').padEnd(20)} ours=${d.ours}`).join('\n'));
