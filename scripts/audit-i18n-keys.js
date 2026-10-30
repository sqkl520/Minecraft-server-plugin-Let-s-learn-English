const fs = require('fs'), path = require('path');

// 1) Collect all message-key string literals referenced in Java via known message APIs
const javaRoot = 'src/main/java';
const apiRe = /(?:getPlayerMessage|getSenderMessage|getMessageLine|getGuiMsg|getMessageSafe|getPlayerMessageSafe|getMessage)\(\s*[^,)]*?,?\s*"([a-z][a-z0-9_.-]+)"/g;
// also keys resolved dynamically (concatenation) are skipped — we check literal ones only
const referenced = new Set();
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.java')) {
      const t = fs.readFileSync(p, 'utf8');
      let m;
      while ((m = apiRe.exec(t))) {
        const k = m[1];
        if (k.includes('.')) referenced.add(k); // dotted keys only (message keys)
      }
    }
  }
}
walk(javaRoot);

// 2) Parse zh.yml leaf keys (2-level and 3-level)
function leafKeys(file) {
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const keys = new Set();
  let l1 = null, l2 = null;
  for (const raw of lines) {
    const line = raw.replace(/\r$/, '');
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const m1 = /^([a-z][a-z0-9-]*):/.exec(line);
    if (m1) { l1 = m1[1]; l2 = null; continue; }
    const m2 = /^  ([a-z][a-z0-9_-]*):/.exec(line);
    if (m2) { l2 = m2[1]; keys.add(l1 + '.' + l2); continue; }
    const m3 = /^    ([a-z][a-z0-9_-]*):/.exec(line);
    if (m3 && l1 && l2) { keys.add(l1 + '.' + l2 + '.' + m3[1]); continue; }
  }
  return keys;
}
const zh = leafKeys('src/main/resources/messages/zh.yml');
const en = leafKeys('src/main/resources/messages/en.yml');

// 3) Referenced-but-missing (only check keys whose top segment is a known message section)
// R10-L5：season/class 补入白名单（Wave 3/4 新增顶层段，此前是扫描盲区）。
const sections = new Set(['general','module','ai-chat','error','word','review','dialogue','achievement','achievement-wall',
 'leaderboard','progress','admin','wordbank','placeholder','join','update','language','help','checkin','scenario','duel',
 'admin-help','plugin-info','gui','main-menu','daily','daily-challenge','word-learning-gui','progress-gui','square',
 'word-bank','calendar','profile','chat-history','wrong-book','onboarding','report','dialogue-gui','season','class','server']);
const missing = [];
// R10-L5：以 '-' 或 '.' 结尾的键是代码里的前缀拼接串（如 "achievement-wall.category-" +
// 枚举名），不是完整字面键 —— 跳过并单独计数，消除假阳性。
const suspectedConcat = [];
for (const k of referenced) {
  const top = k.split('.')[0];
  if (!sections.has(top)) continue;
  if (k.endsWith('-') || k.endsWith('.')) { suspectedConcat.push(k); continue; }
  if (!zh.has(k) || !en.has(k)) missing.push(k + (zh.has(k) ? '' : ' [zh-miss]') + (en.has(k) ? '' : ' [en-miss]'));
}
console.log('referenced dotted keys:', referenced.size);
console.log('suspected concatenation keys (skipped):', suspectedConcat.length);
if (suspectedConcat.length) console.log(suspectedConcat.sort().join('\n'));
console.log('MISSING (referenced but not in yml):');
console.log(missing.length ? missing.sort().join('\n') : '(none)');

// CI 门禁：存在真实缺键（zh 或 en 任一侧缺失）时退出码 1，否则 0。
// suspectedConcat（以 '-'/'.' 结尾的前缀拼接键）已在上面单独计数并跳过，不计入 missing。
// 上面的输出格式保持不变，只补退出码 —— 本地 `node scripts/audit-i18n-keys.js; echo $?` 即可自查。
//
// 用 process.exitCode 而非 process.exit()：退出状态完全等价（0/1），但更安全。
// Node 官方文档指出 process.exit() 会"尽快"退出，可能丢弃尚未完成的 stdout/stderr
// 异步写 —— 在 Linux（CI runner）上 stdout 是管道，写是异步的，缺键清单较长时
// 有丢尾风险；process.exitCode 则等事件循环自然排空后再按该码退出。本脚本无
// 挂起定时器/未关闭句柄，自然退出即达，行为与 process.exit() 一致。
process.exitCode = missing.length ? 1 : 0;
