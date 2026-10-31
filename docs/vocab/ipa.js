const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const CMU = 'C:/Users/ADMINI~1/AppData/Local/Temp/cmudict.dict';

// ARPAbet -> IPA（英式，与现有词库一致：/əˈbaʊt/、/ˈɔːl.səʊ/、/ˌɑːf.təˈnuːn/）。
// 关键差异：CMUdict 是美式，ER 要出 /ə/（英式 there=/ðeə/、worker=/ˈwɜːkə/）。
//
// 【AH2 必须有映射】最初漏了它，后果是**整个元音消失**：
//   someone   = S AH1 M W AH2 N  -> /sʌmwn/   （AH2 没映射 → 输出成 sʌmwn，不是英语音节）
//   understand= AH2 N D ER0 ...  -> /ndəˈstænd/（词首变成纯辅音串）
// 这是结构性错误而非转写风格差异，共影响 19 个词。
const VOWELS = {
  AA: 'ɑː', AE: 'æ', AH0: 'ə', AH1: 'ʌ', AH2: 'ʌ',
  // AH2 = 次级重读的 /ʌ/。CMUdict 里 AH2 只出现在复合词的后半（someone/understand/
  // afternoon），实际读音仍是 /ʌ/ 或弱化成 /ə/；取 /ʌ/ 更接近词典写法。
  AO: 'ɔː', AW: 'aʊ', AY: 'aɪ',
  EH: 'e', ER0: 'ə', ER1: 'ɜː', ER2: 'ə', EY: 'eɪ', IH: 'ɪ', IY: 'iː', OW: 'əʊ',
  OY: 'ɔɪ', UH: 'ʊ', UW: 'uː'
};
const CONS = {
  B: 'b', CH: 'tʃ', D: 'd', DH: 'ð', F: 'f', G: 'ɡ', HH: 'h', JH: 'dʒ',
  K: 'k', L: 'l', M: 'm', N: 'n', NG: 'ŋ', P: 'p', R: 'r', S: 's', SH: 'ʃ',
  T: 't', TH: 'θ', V: 'v', W: 'w', Y: 'j', Z: 'z', ZH: 'ʒ'
};

function arpaToIpa(cmudictLine) {
  const parts = cmudictLine.trim().split(/\s+/).slice(1);

  // 第一遍：逐音素转写，同时记录每个音素在输出串里的 [start,end) 与它的重读等级。
  // 重音符号必须插在"重读元音所在音节的第一个辅音"之前，而不是词首——
  // between = B IH0 T W IY1 N，重音在 IY1（第二音节），插在词首会得到 /bˈɪtwiːn/（错）。
  const units = [];   // {start, end, isVowel, stress}
  let out = '';
  for (const p of parts) {
    const m = p.match(/^([A-Z]+)([0-2])?$/);
    if (!m) continue;
    const base = m[1], stress = m[2];
    const start = out.length;
    if (stress !== undefined) {
      const ipa = VOWELS[base + stress] || VOWELS[base];
      if (!ipa) continue;
      out += ipa;
      units.push({ start, end: out.length, isVowel: true, stress: +stress });
    } else {
      const ipa = CONS[base];
      if (!ipa) continue;
      out += ipa;
      units.push({ start, end: out.length, isVowel: false, stress: -1 });
    }
  }
  if (!out) return null;

  // 【元音合并清理】CMUdict 里 ER0 + 后续 AH0 会连出 /əə/（两个 schwa 相邻），
  // 英语不允许这种；词典写法是只留一个：
  //   general    = JH EH1 N ER0 AH0 L  -> /ˈdʒenəəl/（错）-> /ˈdʒenrəl/（对）
  //   difference = D IH1 F ER0 AH0 N S -> /ˈdɪfəəns/（错）-> /ˈdɪfrəns/（对）
  // 处理：əə -> ə（保一个），且 ER 后面跟 ə 时插入 r（英式非儿化但拼写上仍标 r，
  // 与词典 /ˈdʒenrəl/ 一致）。
  out = out.replace(/əə/g, 'ə');
  out = out.replace(/ərə/g, 'rə');

  const vowels = units.filter(u => u.isVowel);
  // 单音节词不标重音（英语词典惯例）
  if (vowels.length <= 1) return '/' + out + '/';

  const primary = vowels.find(u => u.stress === 1);
  const secondary = vowels.find(u => u.stress === 2);
  if (!primary) return '/' + out + '/';

  // 取"该元音所在音节的起点"：从该元音往前，越过它前面连续的辅音，
  // 停在"上一个元音之后"的第一个辅音处（这就是本音节的声母）。
  function syllableStart(vowelUnit) {
    let idx = units.indexOf(vowelUnit);
    let j = idx - 1;
    while (j >= 0 && !units[j].isVowel) j--;
    // units[j] 是上一个元音（或 -1），本音节的首辅音是 j+1
    return j + 1 < units.length ? units[j + 1].start : vowelUnit.start;
  }

  const inserts = [];
  if (secondary && secondary !== primary) inserts.push({ pos: syllableStart(secondary), sym: 'ˌ' });
  inserts.push({ pos: syllableStart(primary), sym: 'ˈ' });
  // 从后往前插，避免前面的插入影响后面的下标
  inserts.sort((a, b) => b.pos - a.pos);
  for (const ins of inserts) out = out.slice(0, ins.pos) + ins.sym + out.slice(ins.pos);

  return '/' + out + '/';
}

// --- load CMUdict ---
const cmu = new Map();
for (const line of fs.readFileSync(CMU, 'utf8').split('\n')) {
  if (!line || line.startsWith(';;;')) continue;
  const w = line.split(/\s+/)[0].toLowerCase();
  if (!/^[a-z']+$/.test(w)) continue;
  if (!cmu.has(w)) cmu.set(w, []);        // 保留第一个（最常见）读音
  const arr = cmu.get(w);
  if (arr.length < 1) arr.push(line);
}

// --- load missing words ---
const missing = JSON.parse(fs.readFileSync(path.join(VOCAB, 'ecdict-missing.json'), 'utf8'));

let ok = 0, miss = [];
for (const e of missing) {
  const w = e.w.toLowerCase();
  const lines = cmu.get(w);
  if (lines && lines.length) {
    const ipa = arpaToIpa(lines[0]);
    if (ipa) { e.ipa = ipa; ok++; continue; }
  }
  miss.push(w);
}
console.log(`CMUdict hit ${ok}/${missing.length}; miss ${miss.length}`);
if (miss.length) console.log('MISS:', miss.join(' '));

console.log('\n=== SAMPLE IPA ===');
let n = 0;
for (const e of missing) {
  if (!e.ipa || n++ >= 15) continue;
  console.log(`${e.w.padEnd(12)} r=${String(e.rank).padStart(3)}  ${e.ipa.padEnd(22)} ecdict=${(e.phonetic || '-')}`);
}

fs.writeFileSync(path.join(VOCAB, 'ecdict-missing.json'), JSON.stringify(missing, null, 2));
console.log('\nupdated ecdict-missing.json with ipa');
