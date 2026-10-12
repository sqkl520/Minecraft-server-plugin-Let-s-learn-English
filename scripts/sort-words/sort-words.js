#!/usr/bin/env node
'use strict';
/*
 * sort-words.js — P2/S6: one-off alphabetical re-sort of the three word-bank YAMLs.
 *
 * NOT packaged into the plugin JAR: lives in <root>/scripts/sort-words/,
 * outside src/main/resources, so Maven never copies it into target/classes
 * (pom.xml only includes src/main/resources as a resource dir).
 *
 * Guarantees:
 *   - Every entry is an indivisible block of lines (entry start line up to the
 *     line before the next blank separator), moved verbatim — zero byte edits
 *     inside an entry.
 *   - Header lines (up to and including the first 'words:' line) are kept
 *     verbatim and stay first.
 *   - One blank line between entries; whether the file ends with a trailing
 *     newline is preserved as found.
 *   - Sort key: english value, lowercased (values are plain a-zA-Z).
 *
 * Usage:
 *   node scripts/sort-words/sort-words.js <file.yml> [--check]
 *
 * --check: do not write, just report whether the file is already sorted and
 *          print the out-of-order (adjacent inversion) positions.
 *
 * Exit codes: 0 ok; 2 parse/structure error; 3 (--check only) unsorted.
 */
const fs = require('fs');

function fail(msg) { console.error('ERROR: ' + msg); process.exit(2); }

function sortFile(file, checkOnly) {
  const raw = fs.readFileSync(file, 'utf8');
  const hadTrailingNewline = raw.endsWith('\n');
  const lines = raw.split('\n');
  // normalize: drop the single empty string produced by the trailing newline
  if (lines.length && lines[lines.length - 1] === '') lines.pop();

  // 1. header: lines up to and including the first top-level 'words:' line
  let headerEnd = -1;
  for (let i = 0; i < lines.length; i++) {
    if (/^words:\s*$/.test(lines[i])) { headerEnd = i; break; }
  }
  if (headerEnd < 0) fail(file + ': no top-level "words:" line found');
  const header = lines.slice(0, headerEnd + 1);

  // 2. body: split on blank separator lines into entry blocks
  const bodyLines = lines.slice(headerEnd + 1);
  const blocks = [];
  let cur = [];
  for (const line of bodyLines) {
    if (line === '') {
      if (cur.length) { blocks.push(cur); cur = []; }
      // consecutive blanks would be a structure error; guard below
      else fail(file + ': unexpected blank line outside an entry');
    } else {
      cur.push(line);
    }
  }
  if (cur.length) blocks.push(cur);
  if (!blocks.length) fail(file + ': no entries found after "words:"');

  // 3. every block must start with '  - english: "..."'
  const entries = blocks.map((block, bi) => {
    const m = block[0].match(/^  - english: "([A-Za-z]+)"$/);
    if (!m) fail(file + ': block #' + bi + ' does not start with an english field: ' + JSON.stringify(block[0]));
    return { key: m[1].toLowerCase(), english: m[1], block };
  });

  // 4. duplicate detection (report only)
  const seen = new Map();
  const dups = [];
  entries.forEach((e) => {
    if (seen.has(e.key)) dups.push(e.english + ' (positions ' + seen.get(e.key) + ' & ' + entries.indexOf(e) + ')');
    else seen.set(e.key, entries.indexOf(e));
  });

  // 5. out-of-order adjacent pairs (pre-sort state)
  let inversions = 0;
  const invList = [];
  for (let i = 1; i < entries.length; i++) {
    if (entries[i].key < entries[i - 1].key) {
      inversions++;
      invList.push((i + 1) + ': ' + entries[i].english + ' < ' + entries[i - 1].english);
    }
  }
  console.log(file);
  console.log('  entries: ' + entries.length);
  console.log('  duplicates: ' + (dups.length ? dups.join(', ') : 'none'));
  console.log('  out-of-order adjacent pairs: ' + inversions);
  invList.forEach((s) => console.log('    ' + s));

  if (checkOnly) process.exit(inversions ? 3 : 0);

  // 6. stable sort by lowercased english; emit header verbatim followed by
  //    entry blocks separated by single blank lines (as in the source files),
  //    preserving whether the source had a trailing newline
  const sorted = entries.slice().sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  const out = header.slice();
  sorted.forEach((e, i) => {
    if (i > 0) out.push('');
    out.push(...e.block);
  });
  fs.writeFileSync(file, out.join('\n') + (hadTrailingNewline ? '\n' : ''), 'utf8');
  console.log('  -> written (sorted)');
}

const args = process.argv.slice(2);
const checkOnly = args.includes('--check');
const file = args.find((a) => !a.startsWith('--'));
if (!file) { console.error('usage: node scripts/sort-words/sort-words.js <file.yml> [--check]'); process.exit(2); }
sortFile(file, checkOnly);
