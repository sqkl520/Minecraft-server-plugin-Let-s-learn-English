const fs = require('fs'), path = require('path');
const W = path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'words');

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const EX_RE = /^\s{4}example_sentence:\s*"((?:[^"\\]|\\.)*)"/;
const NAMES = /\b(Tom|Mary|John|Jim|Sami|Layla|Charles|Bill|Bob|Alice|Jane|Ken|Mike|Lucy|Betty|Nancy|Hans|Tebboune|Susie|Rye)\b/;

const hits = [];
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const txt = fs.readFileSync(path.join(W, t + '.yml'), 'utf8');
  let cur = null;
  for (const line of txt.split(/\r?\n/)) {
    let m;
    if ((m = line.match(WORD_RE))) { cur = m[1]; continue; }
    if (cur && (m = line.match(EX_RE)) && NAMES.test(m[1])) hits.push({ t, w: cur, ex: m[1] });
  }
}
console.log('含通用人名的例句:', hits.length, '条');
hits.slice(0, 25).forEach(h => console.log(' ', h.t[0], h.w.padEnd(12), h.ex));
