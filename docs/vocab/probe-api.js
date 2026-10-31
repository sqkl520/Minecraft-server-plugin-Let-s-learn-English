const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const PHON_RE = /^\s{4}phonetic:\s*"((?:[^"\\]|\\.)*)"/;
function readBank(tier) {
  const out = []; let cur = null;
  for (const l of fs.readFileSync(path.join(WORDS, tier + '.yml'), 'utf8').split(/\r?\n/)) {
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

// 抽样 40 个（跨档均匀）验证 dictionaryapi.dev 的 IPA 与词库一致率
const sample = [];
const step = Math.floor(gold.length / 40);
for (let i = 0; i < gold.length && sample.length < 40; i += step) sample.push(gold[i]);

const https = require('https');
function get(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { timeout: 20000, rejectUnauthorized: false }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
  });
}

(async () => {
  let exact = 0, loose = 0, fail = 0, diff = [];
  for (const g of sample) {
    try {
      const r = await get('https://api.dictionaryapi.dev/api/v2/entries/en/' + encodeURIComponent(g.english));
      if (r.status !== 200) { fail++; continue; }
      const j = JSON.parse(r.body);
      // 取第一个有 phonetic 的条目
      let ipa = null;
      for (const e of j) {
        if (e.phonetic) { ipa = e.phonetic; break; }
        for (const p of (e.phonetics || [])) if (p.text) { ipa = p.text; break; }
        if (ipa) break;
      }
      if (!ipa) { fail++; continue; }
      const norm = s => s.replace(/[\/ˈˌ.\s]/g, '');
      if (ipa === g.phonetic) exact++;
      if (norm(ipa) === norm(g.phonetic)) loose++;
      else diff.push({ w: g.english, bank: g.phonetic, api: ipa });
    } catch (e) { fail++; }
    await new Promise(r => setTimeout(r, 120)); // 别打太快
  }
  console.log(`抽样 ${sample.length}，失败 ${fail}`);
  console.log(`完全一致 : ${exact}/${sample.length - fail}`);
  console.log(`忽略重音 : ${loose}/${sample.length - fail}`);
  console.log('\n不一致:');
  console.log(diff.map(d => `  ${d.w.padEnd(14)} bank=${d.bank.padEnd(20)} api=${d.api}`).join('\n'));
})();
