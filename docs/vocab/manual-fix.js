const fs = require('fs'), path = require('path');
const VOCAB = __dirname;

// 人工订正表：由「句法启发式」判错、经人工核验后修正的词。
// 每条都要写明为什么——这是唯一允许覆盖自动判定的地方。
const MANUAL_FIX = {
  well: {
    pos: 'adverb', chinese: '好；令人满意地',
    example: 'She speaks English very well.',
    why: '启发式把 "do well to" 判成 verb(涌出)；实际是 adv. 好'
  },
  as: {
    pos: 'conjunction', chinese: '当…时；因为；如同',
    example: 'As I was leaving, the phone rang.',
    why: '"the same as having" 里 as 是比较连接词，不是 adv. 同样地'
  },
  so: {
    pos: 'conjunction', chinese: '所以；因此',
    example: 'It was raining, so we stayed at home.',
    why: '"So I\'ll get you..." 是句首连接词，不是 adv. 如此'
  },
  since: {
    pos: 'conjunction', chinese: '既然；自从',
    example: 'Since you are busy, I will go alone.',
    why: '"Since it\'s important, I\'ll attend to it" 是 conj. 既然，不是 adv. 自那时以后'
  },
  even: {
    pos: 'adverb', chinese: '甚至；即使',
    example: 'He didn\'t even say goodbye.',
    why: '"Even though" 是连词搭配，被误判为 verb'
  },
  now: {
    pos: 'adverb', chinese: '现在；此刻',
    example: 'I am busy now.',
    why: '"There\'s no other way than this now" 里 now 是时间副词，不是 noun'
  },
  work: {
    pos: 'verb', chinese: '工作；运转',
    example: 'She works in a hospital.',
    why: '词库该词第一义项应是"工作(动词)"这一最常见用法；例句也要对上'
  },
  other: {
    pos: 'adjective', chinese: '其他的；另外的',
    example: 'The other students went home.',
    why: '"the other went away" 里 other 是代词，需换成形容词用法'
  },
  yet: {
    pos: 'adverb', chinese: '还；仍然',
    example: 'He has not arrived yet.',
    why: '回退项，确认 adv. 还'
  },
  around: {
    pos: 'preposition', chinese: '在…周围；大约',
    example: 'They sat around the table.',
    why: '"fool around" 是动词短语，不是介词'
  },
  across: {
    pos: 'preposition', chinese: '穿过；横过',
    example: 'She walked across the street.',
    why: '"Swimming across the lake almost finished me" 过难，换简单句'
  },
  until: {
    pos: 'preposition', chinese: '直到',
    example: 'Wait until tomorrow.',
    why: '"You might as well wait until" 含固定搭配，换简单句'
  },
  whether: {
    pos: 'conjunction', chinese: '是否',
    example: 'I don\'t know whether he will come.',
    why: '回退项，确认 conj. 是否'
  },
  else: {
    pos: 'adjective', chinese: '其他的；别的',
    example: 'Is there anything else you want?',
    why: '回退项，确认 adj./adv. 别的'
  },
  bag: {
    pos: 'noun', chinese: '包；袋子',
    example: 'She put the books in her bag.',
    why: '"My schoolbag is too heavy." 里 schoolbag 是复合词，不是 bag'
  },
  cut: {
    pos: 'verb', chinese: '切；割；削减',
    example: 'He cut the bread into two pieces.',
    why: '"cutting lending to industrial borrowers" 是金融义，对学习者太抽象'
  },
  leave: {
    pos: 'verb', chinese: '离开；留下',
    example: 'I leave home at seven every morning.',
    why: '"There\'s a secret path on the left" 里 left 是方向名词，与 leave 无关（三重错配：词性/释义/例句全不对）'
  },
  // ---- 以下 11 条由红队找到：句法判定"词性对不对"，但判不出"释义是不是这个义项" ----
  // 它们全都通过了句法检查（位置确实像那个词性），却取了 ECDICT 里另一个义项的释义，
  // 结果教的是完全不同的意思。这一条比音标问题严重：音标错只是读错，释义错是学错。
  still: {
    pos: 'adverb', chinese: '仍然；还',
    example: 'It is still fresh in my memory.',
    why: '把 noun 义项「蒸馏」当成了释义；例句里是 adv. 仍然'
  },
  down: {
    pos: 'adverb', chinese: '向下；在下面',
    example: 'Please write down your name.',
    why: '取了 noun 义项「丘陵」；例句 take down 里是 adv. 向下'
  },
  lot: {
    pos: 'noun', chinese: '大量；许多',
    example: 'You know quite a lot about music.',
    why: '取了 noun 义项「运气」；a lot 是"大量"，且原例句含生词 sumo'
  },
  service: {
    pos: 'noun', chinese: '服务；服务业',
    example: 'The hotel offers good service.',
    why: '取了 adj. 义项「武装部队的」；例句 service economy 里是 noun 服务业'
  },
  special: {
    pos: 'adjective', chinese: '特别的；特殊的',
    example: 'Today is a special day for us.',
    why: '取了 noun 义项「专辑」；例句 on special occasions 里是 adj. 特别的'
  },
  social: {
    pos: 'adjective', chinese: '社会的；社交的',
    example: 'Social media is very popular now.',
    why: '取了 noun 义项「联欢会」；例句 social work 里是 adj. 社会的'
  },
  staff: {
    pos: 'noun', chinese: '员工；全体职员',
    example: 'Our staff is eager to help you.',
    why: '取了 adj. 义项「职员的」；例句里是 noun 员工'
  },
  film: {
    pos: 'noun', chinese: '电影；影片',
    example: 'They have all gone to see the film.',
    why: '取了「软片」(摄影胶片) 这个罕用义项；例句 see the film 是电影'
  },
  issue: {
    pos: 'noun', chinese: '问题；议题',
    example: 'We should face up to this issue.',
    why: '取了「发行」这个义项；例句里是"问题"'
  },
  course: {
    pos: 'noun', chinese: '课程；进程',
    example: 'This course is very useful for beginners.',
    why: '原例句 "Benefits of course should exceed the costs" 里 course 是"进程/过程"，与释义「课程」不匹配'
  },
  stay: {
    pos: 'verb', chinese: '停留；待',
    example: 'I would like to stay for one night.',
    why: '取了 noun 义项「制止」；例句里是 verb 停留'
  },
  here: {
    pos: 'adverb', chinese: '这里；在这里',
    example: 'Please come here and sit down.',
    why: '原例句 "Except that here, it\'s not so simple" 结构复杂，且 here 标成 noun'
  },
  // ---- 以下 15 条：Tatoeba 的通用占位人名（Tom/Mary/Sami/Layla…）与专有名词 ----
  // 这些名字对学习者没有任何信息量，且会让例句显得明显是语料库拼的。
  // 统一换成不含人名的自然例句。
  and: {
    pos: 'conjunction', chinese: '和；并且',
    example: 'She bought bread and milk.',
    why: '原例句 "Tom and Jim are brothers." 含两个占位人名（注：and 是**老词**，本表不生效，真正生效处在 emit.js 的 LEGACY_FIXES）'
  },
  his: {
    pos: 'determiner', chinese: '他的',
    example: 'He put his book on the desk.',
    why: '原例句 "His name is Tom." 含占位人名且过短（注：his 是**老词**，真正生效处在 emit.js 的 LEGACY_FIXES）'
  },
  call: {
    pos: 'verb', chinese: '呼叫；打电话；称为',
    example: 'Please call me when you arrive.',
    why: '原例句 "Rye was called the grain of poverty." 含 rye/poverty 两个生词且语义生僻'
  },
  company: {
    pos: 'noun', chinese: '公司；陪伴',
    example: 'She works for a small company.',
    why: '原例句 "Hans sold the patent to a company." 含占位人名与 patent'
  },
  between: {
    pos: 'preposition', chinese: '在…之间',
    example: 'The bank is between the school and the park.',
    why: '原例句含 Tom/Mary 两个占位人名'
  },
  become: {
    pos: 'verb', chinese: '变成；成为',
    example: 'The weather is becoming colder.',
    why: '原例句含 Mary/Charles 两个占位人名与 fond'
  },
  few: {
    pos: 'determiner', chinese: '少数的；几个',
    example: 'I have a few questions about this.',
    why: '原例句含 Mike 与 Florida'
  },
  program: {
    pos: 'noun', chinese: '节目；程序',
    example: 'This program helps students learn English.',
    why: '原例句含 Tom 与 JavaScript（专有名词）'
  },
  reason: {
    pos: 'noun', chinese: '原因；理由',
    example: 'There is no reason to worry.',
    why: '原例句含 Mary 与 satisfied'
  },
  city: {
    pos: 'noun', chinese: '城市',
    example: 'This city is very large.',
    why: '原例句含 Tom'
  },
  within: {
    pos: 'preposition', chinese: '在…之内',
    example: 'Please reply within three days.',
    why: '原例句含 Tebboune/Algerian 两个专有名词'
  },
  general: {
    pos: 'adjective', chinese: '一般的；总的',
    example: 'In general, the weather here is mild.',
    why: '原例句含 Susie；且 "In general" 里 general 是形容词固定搭配，改后仍是'
  },
  toward: {
    pos: 'preposition', chinese: '朝向；对于',
    example: 'She walked toward the door.',
    why: '原例句含 Sami/Layla 两个占位人名'
  },
  agree: {
    pos: 'verb', chinese: '同意；赞成',
    example: 'I agree with your idea.',
    why: '原例句含 Bob'
  },
  phone: {
    pos: 'noun', chinese: '电话；手机',
    example: 'My phone is on the table.',
    why: '原例句含 Jim 且用了 phoning 这个动词形态'
  },
  together: {
    pos: 'adverb', chinese: '一起；共同',
    example: 'We study English together every day.',
    why: '原例句是"英法德禁止垃圾进口"，三个专有名词 + 政策语境，对学习者完全不合适'
  },
};

const aligned = JSON.parse(fs.readFileSync(path.join(VOCAB, 'aligned.json'), 'utf8'));
let fixed = 0, unmatched = [];
for (const [w, fix] of Object.entries(MANUAL_FIX)) {
  if (!fix) continue;
  const e = aligned.find(x => x.w.toLowerCase() === w);
  if (!e) { unmatched.push(w); continue; }
  e.pos = fix.pos;
  e.chinese = fix.chinese;
  e.example_final = fix.example;
  e.aligned = true;
  e.pos_source = 'manual';
  e.manual_why = fix.why;
  fixed++;
}
console.log(`人工订正 ${fixed} 条`);
if (unmatched.length) console.log('订正表里但词库里没有:', unmatched.join(' '));

// 校验：人工例句也必须包含目标词（允许词形变化：works/working/work 都算）
const bad = [];
for (const [w, fix] of Object.entries(MANUAL_FIX)) {
  if (!fix) continue;
  const stem = w.replace(/e$/, '');
  const re = new RegExp('\\b' + stem + '(s|es|ed|d|ing|' + w.slice(-1) + ')?\\b', 'i');
  if (!re.test(fix.example)) bad.push(w + ' -> ' + fix.example);
}
if (bad.length) console.log('!! 人工例句不含目标词:', '\n  ' + bad.join('\n  '));

fs.writeFileSync(path.join(VOCAB, 'aligned.json'), JSON.stringify(aligned, null, 2));

// 统计
const bySource = {};
for (const e of aligned) bySource[e.pos_source || 'none'] = (bySource[e.pos_source || 'none'] || 0) + 1;
console.log('\n对齐来源分布:', JSON.stringify(bySource, null, 0));
