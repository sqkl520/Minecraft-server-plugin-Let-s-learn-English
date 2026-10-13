const fs = require('fs');
const path = process.argv[2] || 'en.yml';
let lines = fs.readFileSync(path, 'utf8').split('\n');

const names = lines.filter(l => /^[a-z][a-z0-9-]*:/.test(l)).map(l => l.trim());
for (const m of ['square:', 'main-menu:', 'word:']) {
  if (names.filter(n => n === m).length !== 1) { console.log('UNEXPECTED DUP/MISSING', m); process.exit(1); }
}

const block = [
'',
'# ============================================================',
'#  Parent Report -- Wave 2 (Domain 6)',
'#  File templates are plain text (no color codes / no prefix).',
'#  report.enabled/period/language etc. are read from config.yml.',
'# ============================================================',
'report:',
'  # --- file templates (plain text) ---',
'  title: "Learning Report"',
'  meta-player: "Student: {player}"',
'  meta-server: "Server: {server}"',
'  meta-period: "Period: {from} ~ {to}"',
'  meta-generated: "Generated: {time}"',
'  weekday-mon: "Mon"',
'  weekday-tue: "Tue"',
'  weekday-wed: "Wed"',
'  weekday-thu: "Thu"',
'  weekday-fri: "Fri"',
'  weekday-sat: "Sat"',
'  weekday-sun: "Sun"',
'  section-overview: "[Overview]"',
'  overview-days: "Active days: {days}"',
'  overview-correct: "Words correct: {correct}"',
'  overview-accuracy: "Accuracy: {accuracy} ({correct}/{total})"',
'  overview-score: "Score: {score}"',
'  overview-minutes: "Estimated study time: ~{minutes} min (at {seconds}s per question)"',
'  section-daily: "[Daily Detail]"',
'  daily-header: "Date     Day    Answered  Correct  Accuracy  Score"',
'  daily-row: "{date}   {weekday}   {answered}   {correct}   {accuracy}   {score}"',
'  section-weak-words: "[Weak Words] ({k} items)"',
'  weak-word-item: "{word} ({chinese}) -- wrong {count} time(s)"',
'  weak-word-item-no-cn: "{word} -- wrong {count} time(s)"',
'  weak-focus: "Most mistakes are in the {difficulty} tier; review those first."',
'  section-cumulative: "[Overall Progress]"',
'  cumulative-line: "{words} words correct in total across {sessions} sessions."',
'  cumulative-extra: "Achievements unlocked: {unlocked}/{total}; check-in streak: {streak} day(s)."',
'  cumulative-achievements-recent: "Newly unlocked this period: {count}."',
'  cumulative-ai: "AI conversations: {total} total ({recent} this period)."',
'  cumulative-challenge: "Daily challenges completed this period: {done}/{all}."',
'  section-advice: "[A Note for Parents]"',
'  advice-none: "No study records this week yet - have a chat about the study plan~"',
'  advice-good: "Steady studying this week - keep encouraging them!"',
'  advice-up: "Accuracy is improving - praise goes a long way!"',
'  advice-down: "Accuracy dipped a little this week; reviewing wrong words together helps."',
'  advice-weak: "There are some tricky words - reading and writing them together helps."',
'  advice-default: "A little practice every day adds up."',
'  section-note: "[Notes]"',
'  note-line: "This report was generated automatically by the Lets Learn English plugin (study time estimated at ~{seconds}s per question)."',
'  no-data-file: "No learning data in this period."',
'  batch-title: "Server-wide Learning Report"',
'  batch-count-line: "{count} students in total"',
'  batch-table-title: "[Student Comparison]"',
'  batch-table-header: "Student     Days   Correct  Accuracy  Score"',
'  batch-table-row: "{name}   {days}   {correct}   {accuracy}   {score}"',
'  batch-student-header: "[Summary for {player}]"',
'  batch-weak-line: "Weak words: {items}"',
'  batch-truncated-file: "(more than {max} students; showing first {max})"',
'  # --- chat feedback ---',
'  chat-header: "{prefix}&6===== Report for {player} generated ====="',
'  chat-overview: "{prefix}&7{from} ~ {to} | &e{days} &7day(s) | &e{correct} &7correct | accuracy &e{accuracy}"',
'  chat-weak: "{prefix}&7Weak words: &e{words}"',
'  generated: "{prefix}&aReport saved: &e{file}"',
'  no-data: "{prefix}&eNo learning data for that player/period"',
'  failed: "{prefix}&cReport generation failed: {reason}"',
'  disabled: "{prefix}&7The parent report feature is disabled~"',
'  hidden: "{prefix}&7That player is hidden from the square; report skipped per privacy settings"',
'  period-invalid: "{prefix}&cInvalid period: {input} (use week / lastweek / <N>d, N in 1-90)"',
'  usage: "{prefix}&cUsage: /lea report <player|all> [week|lastweek|<N>d]"',
'  batch-start: "{prefix}&7Generating report for {count} students, please wait..."',
'  batch-complete: "{prefix}&aServer-wide report generated: &e{file} ({count} students){truncated}"',
'  batch-truncated: "{prefix}&eToo many students; report includes the first {max} only"',
];

const feedBlock = [
'  feed:',
'    mode-label: "&eFeed"',
'    empty: "&7The square is quiet~ Finish a lesson to post the first update!"',
'    session: "&f{player} finished a &e{mode}&f session"',
'    session-detail: "&7Correct: &a{correct}&7/&e{total}"',
'    session-merged: "&f{player} completed &e{count} &fsessions today"',
'    achievement: "&f{player} unlocked the achievement &6{achievement}&f!"',
'    achievement-merged: "&f{player} unlocked &e{count}&f achievements today, latest: &6{achievement}"',
'    checkin: "&f{player} checked in &e{streak}&f days in a row"',
'    hidden-detail: "&7Completed a learning session"',
'    cheer-count: "&cCheers: {count}"',
'    cheer-hint: "&7Left-click: cheer / Right-click: profile"',
'    own-hint: "&dLeft-click: see who cheered you"',
'    cheered: "&a[ok] You cheered"',
'    cheer-success: "{prefix}&aYou cheered &e{player}&a learning update!"',
'    cheer-already: "{prefix}&7You already cheered this update~"',
'    cheer-own: "{prefix}&7You cannot cheer your own update~"',
'    cheer-gone: "{prefix}&7This update is gone already~"',
'    cheer-hidden: "{prefix}&7That player is hidden right now~"',
'    cheerers: "{prefix}&d{names} &fcheered your update! &7({count} total)"',
'    cheerers-none: "{prefix}&7No cheers on this update yet~"',
'    notify-online: "{prefix}&e{player} &fcheered your learning update! &7Use &e/le square &7to view"',
'    notify-join: "{prefix}&eYour learning updates received &c{count} &echeers while you were away! &7Use &e/le square &7to see who~"',
];

const mainMenuBlock = [
'  duel: "&d&lWord Duel"',
'  duel-lore: "&7Challenge online players"',
'  checkin: "&e&lDaily Check-in"',
'  checkin-lore: "&7Check in daily for coin rewards"',
];

const wordBlock = [
'  mode-disabled: "{prefix}&cThat learning mode has been disabled by the server admin~"',
'  no-modes-enabled: "{prefix}&cAll learning modes are disabled. Please contact the admin."',
];

function topIndices(arr) {
  const out = [];
  for (let i = 0; i < arr.length; i++) if (/^[a-z][a-z0-9-]*:/.test(arr[i])) out.push(i);
  return out;
}
function insertIntoSection(sectionName, text) {
  const tops = topIndices(lines);
  for (let ti = 0; ti < tops.length; ti++) {
    if (lines[tops[ti]].trim() === sectionName) {
      let end = ti + 1 < tops.length ? tops[ti + 1] : lines.length;
      while (end - 1 > tops[ti] && lines[end - 1].trim() === '') end--;
      lines.splice(end, 0, ...text);
      return true;
    }
  }
  return false;
}

if (!insertIntoSection('square:', feedBlock)) { console.log('square FAIL'); process.exit(1); }
if (!insertIntoSection('main-menu:', mainMenuBlock)) { console.log('main-menu FAIL'); process.exit(1); }
if (!insertIntoSection('word:', wordBlock)) { console.log('word FAIL'); process.exit(1); }

lines.push(...block);
while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();

fs.writeFileSync(path, lines.join('\n') + '\n');
console.log('EN MERGED OK, lines=' + lines.length);
