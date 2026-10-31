const fs = require('fs'), path = require('path');
const VOCAB = __dirname;
const WORDS = path.join(VOCAB, '..', '..', 'src', 'main', 'resources', 'words');

const WORD_RE = /^\s{2}-\s+english:\s*"((?:[^"\\]|\\.)*)"/;
const existing = new Set();
for (const t of ['beginner', 'intermediate', 'advanced']) {
  const txt = fs.readFileSync(path.join(WORDS, t + '.yml'), 'utf8');
  let m; const re = new RegExp(WORD_RE.source, 'gm');
  while ((m = re.exec(txt)) !== null) existing.add(m[1].toLowerCase());
}

const aligned = JSON.parse(fs.readFileSync(path.join(VOCAB, 'aligned.json'), 'utf8'));
const dup = aligned.filter(e => existing.has(e.w.toLowerCase()));

console.log('现有词库唯一词数 :', existing.size);
console.log('新词条目数       :', aligned.length);
console.log('与现有词库重复   :', dup.length);
console.log('重复词（前 60）  :');
console.log('  ' + dup.slice(0, 60).map(x => x.w).join(' '));
