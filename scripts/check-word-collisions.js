#!/usr/bin/env node
'use strict';
/*
 * check-word-collisions.js — 词库中文释义撞车扫描（只读，零第三方依赖）。
 *
 * 为什么需要它：AnswerChecker.buildChoiceOptions() 在生成 choice 干扰项时，会按
 * [;；,，、] 把 chinese 切成“义项集合”，任一义项与答案相同即视为撞车，该候选会被
 * 从正常干扰项池剔除，退到 soft/hard 兜底。释义里用分号堆多个义项会放大撞车面，
 * 而 scripts/gen-words.js 的 validate() 不检查这一点（它只看字段类型/模板齐全）。
 *
 * 用法：
 *   node scripts/check-word-collisions.js                       # 扫三档，完整撞车对
 *   node scripts/check-word-collisions.js beginner.yml          # 只扫指定文件
 *   node scripts/check-word-collisions.js --cross               # 附带跨档撞车（默认只档内）
 *   node scripts/check-word-collisions.js --quiet               # 只输出计数与退出码
 *
 * 退出码：0 = 无“完全重复”撞车；1 = 存在完全重复（hard，同一条释义）；2 = 解析失败。
 * 仅“部分义项重叠”（soft）默认只报告不计失败——它往往是真实近义关系。
 *
 * 归一化规则与 AnswerChecker.normalizeDefinition() 对齐：
 *   NFKC → 去零宽字符 → trim → 折叠空白 → 去空白 → 去末尾标点。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const WORDS_DIR = path.join(ROOT, 'src', 'main', 'resources', 'words');
const DEFAULT_FILES = ['beginner.yml', 'intermediate.yml', 'advanced.yml'];

const ZERO_WIDTH = /[​-‍﻿]/g;
const WHITESPACE = /\s+/g;
const DEFINITION_TRAILING = /[\s。．.,，、;；:：!！?？/／|｜()（）[\]【】]+$/;
const DEFINITION_SEPARATOR = /[;；/／]/;
// Java 侧先剔除括号注释再比较义项（AnswerChecker.DEFINITION_ANNOTATION）；
// 脚本必须同口径，否则 "他们(宾格…)" 与 "他们" 会被误判为不撞车（P1 红队实测漏报 2 对）。
const DEFINITION_ANNOTATION = /[（(][^（()）]*[)）]/g;

function normalizeDefinition(raw) {
  if (raw == null) return '';
  let text = String(raw).normalize('NFKC').replace(ZERO_WIDTH, '').trim();
  if (!text) return '';
  text = text.replace(WHITESPACE, ' ');
  text = text.replace(WHITESPACE, '');
  // 与 AnswerChecker 一致：整条都被括号包住时不要归零，否则该词会被当成"无释义"而漏掉真撞车。
  const stripped = text.replace(DEFINITION_ANNOTATION, '');
  if (stripped.length > 0) text = stripped;
  return text.replace(DEFINITION_TRAILING, '');
}

function definitionSegments(chinese) {
  const full = normalizeDefinition(chinese);
  const out = new Set();
  if (!full) return out;
  for (const part of full.split(DEFINITION_SEPARATOR)) {
    const seg = normalizeDefinition(part);
    if (seg) out.add(seg);
  }
  return out;
}

/** 读词库：只取 english/chinese 两列，容忍多行字段之间互不干扰。 */
function loadEntries(file) {
  const text = fs.readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/);
  const entries = [];
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^  - english:\s*"((?:[^"\\]|\\.)*)"\s*$/);
    if (!m) continue;
    const english = m[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
    let chinese = '';
    for (let j = i + 1; j < lines.length && j < i + 4; j++) {
      const c = lines[j].match(/^    chinese:\s*"(.*)"\s*$/);
      if (c) { chinese = c[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\'); break; }
      if (/^  - english:/.test(lines[j])) break;
    }
    entries.push({ english, chinese, line: i + 1, file: path.basename(file), segs: definitionSegments(chinese) });
  }
  return entries;
}

function compare(a, b) {
  const shared = [];
  for (const s of a.segs) if (b.segs.has(s)) shared.push(s);
  if (!shared.length) return null;
  const hard = normalizeDefinition(a.chinese) === normalizeDefinition(b.chinese);
  return { a, b, shared, hard };
}

(function main() {
  const argv = process.argv.slice(2);
  const quiet = argv.includes('--quiet');
  const cross = argv.includes('--cross');
  const named = argv.filter((x) => !x.startsWith('--') && x.endsWith('.yml'));
  const files = (named.length ? named : DEFAULT_FILES).map((f) =>
    path.isAbsolute(f) || f.includes('/') || f.includes('\\') ? path.resolve(f) : path.join(WORDS_DIR, f));

  let banks;
  try {
    banks = files.map((f) => ({ file: f, entries: loadEntries(f) }));
  } catch (err) {
    console.error('READ ERROR: ' + err.message);
    process.exit(2);
  }

  let pairs = 0, hardPairs = 0;
  const groups = named.length && !cross ? [banks] : cross ? [banks] : banks.map((b) => [b]);

  for (const group of groups) {
    const all = group.flatMap((b) => b.entries);
    for (let i = 0; i < all.length; i++) {
      for (let j = i + 1; j < all.length; j++) {
        const a = all[i], b = all[j];
        if (a.file === b.file && a.english.toLowerCase() === b.english.toLowerCase()) continue;
        const c = compare(a, b);
        if (!c) continue;
        pairs++;
        if (c.hard) hardPairs++;
        if (!quiet) {
          const where = a.file === b.file ? a.file : a.file + ' × ' + b.file;
          console.log((c.hard ? '[HARD] ' : '[soft] ') + a.english + ' 「' + a.chinese + '」'
            + '  <->  ' + b.english + ' 「' + b.chinese + '」'
            + '   shared=' + JSON.stringify(c.shared) + '   (' + where + ')');
        }
      }
    }
  }

  const total = banks.reduce((n, b) => n + b.entries.length, 0);
  if (!quiet) console.log('');
  console.log('files: ' + banks.map((b) => path.basename(b.file) + '(' + b.entries.length + ')').join(' + ')
    + '  total=' + total);
  console.log('collision pairs: ' + pairs + ' (hard=' + hardPairs + ', soft=' + (pairs - hardPairs) + ')');
  process.exit(hardPairs > 0 ? 1 : 0);
})();
