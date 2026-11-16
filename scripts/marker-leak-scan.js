// 判定内部批次编号是否泄漏到「非注释」位置。
//
// 为什么不能用正则扫全文：Java 里 `//` 可能是字符串字面量的一部分（URL），
// 引号也可能是字符字面量（'/'）。必须做真正的词法扫描。
//
// 为什么脚本放在 scripts/ 而不是 target/：`mvn clean` 会删掉 target/，
// 第一版脚本就这么丢过一次。
const fs = require('fs'), path = require('path');

const ROOT = path.join(__dirname, '..', 'src', 'main', 'java');

/** 把 Java 源码切成 [{kind, text, line}]，kind ∈ code|comment|string|char|textblock */
function lex(src) {
  const segs = [];
  let i = 0, line = 1, codeStart = 0;
  const n = src.length;
  const flush = (end) => {
    if (end > codeStart) segs.push({ kind: 'code', text: src.slice(codeStart, end), line });
  };

  while (i < n) {
    const c = src[i], c2 = src[i + 1];

    if (c === '/' && c2 === '/') {
      flush(i);
      const start = i, startLine = line;
      i += 2;
      while (i < n && src[i] !== '\n') i++;
      segs.push({ kind: 'comment', text: src.slice(start, i), line: startLine });
      codeStart = i;
      continue;
    }
    if (c === '/' && c2 === '*') {
      flush(i);
      const start = i, startLine = line;
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') line++;
        i++;
      }
      i = Math.min(i + 2, n);
      segs.push({ kind: 'comment', text: src.slice(start, i), line: startLine });
      codeStart = i;
      continue;
    }
    if (c === '"' && c2 === '"' && src[i + 2] === '"') {
      flush(i);
      const start = i, startLine = line;
      i += 3;
      while (i < n && !(src[i] === '"' && src[i + 1] === '"' && src[i + 2] === '"')) {
        if (src[i] === '\\') i++;
        if (src[i] === '\n') line++;
        i++;
      }
      i = Math.min(i + 3, n);
      segs.push({ kind: 'textblock', text: src.slice(start, i), line: startLine });
      codeStart = i;
      continue;
    }
    if (c === '"') {
      flush(i);
      const start = i, startLine = line;
      i++;
      while (i < n && src[i] !== '"') {
        if (src[i] === '\\') i++;
        i++;
      }
      i = Math.min(i + 1, n);
      segs.push({ kind: 'string', text: src.slice(start, i), line: startLine });
      codeStart = i;
      continue;
    }
    if (c === "'") {
      flush(i);
      const start = i, startLine = line;
      i++;
      while (i < n && src[i] !== "'") {
        if (src[i] === '\\') i++;
        i++;
      }
      i = Math.min(i + 1, n);
      segs.push({ kind: 'char', text: src.slice(start, i), line: startLine });
      codeStart = i;
      continue;
    }
    if (c === '\n') line++;
    i++;
  }
  flush(n);
  return segs;
}

// 内部批次编号的判定规则。
// 注意 R-\d{1,3}（R-16 / R-28 / R-39）与 R\d-... 是**两种不同形态**，
// 第一版只写了后者，于是 AIChatManager 的 3 条 WARNING 和 PlayerDataStore 的
// 3 条 WARNING 全部漏报 —— 而它们恰恰是仅有的几处生产日志文案。
const MARKERS = [
  /\bR\d{1,2}-[A-Za-z0-9]{1,6}\b/g,
  /\bR-\d{1,3}\b/g,
  /\bMC-\d{1,2}\b/g,
  /\bround\d{1,2}\b/g,
  /\bwave\d{1,2}\b/g,
  /\bPF-[A-Z]{2,5}-\d{2}\b/g,
  /\bslice-\d{2}\b/g,
];

function scanFile(file) {
  const src = fs.readFileSync(file, 'utf8');
  const hits = [];
  for (const seg of lex(src)) {
    for (const re of MARKERS) {
      re.lastIndex = 0;
      let m;
      while ((m = re.exec(seg.text)) !== null) {
        const before = seg.text.slice(0, m.index);
        hits.push({
          kind: seg.kind,
          marker: m[0],
          line: seg.line + (before.match(/\n/g) || []).length,
        });
      }
    }
  }
  return hits;
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const files = walk(ROOT);
const byKind = {};
const nonComment = [];
let total = 0;

for (const f of files) {
  for (const h of scanFile(f)) {
    total++;
    byKind[h.kind] = (byKind[h.kind] || 0) + 1;
    if (h.kind !== 'comment') nonComment.push({ file: path.relative(ROOT, f), ...h });
  }
}

console.log('扫描文件数:', files.length);
console.log('标记总数  :', total);
console.log('\n=== 按词法位置分类 ===');
for (const [k, v] of Object.entries(byKind).sort((a, b) => b[1] - a[1])) {
  console.log(`  ${k.padEnd(10)} ${v}`);
}

console.log(`\n=== 非注释位置命中（${nonComment.length} 处，真正的泄漏风险）===`);
for (const h of nonComment) {
  console.log(`  [${h.kind}] ${h.file}:${h.line}  ${h.marker}`);
}
