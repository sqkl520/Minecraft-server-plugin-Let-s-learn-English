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
const sections = new Set(['general','module','ai-chat','error','word','review','dialogue','achievement','achievement-wall',
 'leaderboard','progress','admin','wordbank','placeholder','join','update','language','help','checkin','scenario','duel',
 'admin-help','plugin-info','gui','main-menu','daily','daily-challenge','word-learning-gui','progress-gui','square',
 'word-bank','calendar','profile','chat-history','wrong-book','onboarding','report','dialogue-gui']);
const missing = [];
for (const k of referenced) {
  const top = k.split('.')[0];
  if (!sections.has(top)) continue;
  if (!zh.has(k) || !en.has(k)) missing.push(k + (zh.has(k) ? '' : ' [zh-miss]') + (en.has(k) ? '' : ' [en-miss]'));
}
console.log('referenced dotted keys:', referenced.size);
console.log('MISSING (referenced but not in yml):');
console.log(missing.length ? missing.sort().join('\n') : '(none)');
