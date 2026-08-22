from pathlib import Path

ENGINE = Path('app/src/main/java/com/jingwei/aikeyboard/PinyinEngine.java')
LEXICON = Path('app/src/main/assets/pinyin_lexicon.tsv')

e = ENGINE.read_text(encoding='utf-8')

old = '''        // Earlier words in a lexicon row are treated as more common.\n        // Multi-character words/phrases get a modest bonus so normal phrases\n        // beat improbable chains of unrelated single characters.\n        int score = 1000 - Math.min(rank, 50) * 12 + Math.min(word.length(), 6) * 45;'''
new = '''        // V0.10 ranking: score by covered Chinese characters, not by token count.\n        // The old +1000-per-token formula accidentally rewarded splitting a\n        // sentence into many unrelated single characters. Longer known phrases\n        // must beat fragmented paths, while single characters remain a fallback.\n        int score = Math.min(word.length(), 8) * 220 - Math.min(rank, 50) * 8;'''
if old in e:
    e = e.replace(old, new)
elif new not in e:
    raise RuntimeError('T9 token score block not recognized')

if 'int score = state.score + token.score - 80;' in e:
    e = e.replace(
        'int score = state.score + token.score - 80;',
        'int score = state.score + token.score - 120;'
    )
elif 'int score = state.score + token.score - 120;' not in e:
    raise RuntimeError('T9 segmentation score block not recognized')

ENGINE.write_text(e, encoding='utf-8')

extra = '''\n# V0.10 common chat phrase layer\nwojintianyao\t我今天要\nwojintianxiang\t我今天想\nwojintianwanshangyao\t我今天晚上要\nwojintianwanshangxiang\t我今天晚上想\nzhunbeichuqu\t准备出去\nchuquchifan\t出去吃饭\nchifanranhou\t吃饭然后\nranhouhuilai\t然后回来\nhuilaixuexi\t回来学习\nranhoujixu\t然后继续\njixuxuexi\t继续学习\nkaishixuexi\t开始学习\nwozhidaole\t我知道了\nwomingbaile\t我明白了\nmeiwenti\t没问题\nmeiguanxi\t没关系\nxiexieni\t谢谢你\nmazhang\t马上\ndengyixia\t等一下\nshaodeng\t稍等\nkeyide\t可以的\nhaodehaode\t好的好的\nwojuede\t我觉得\nwojuedezhege\t我觉得这个\nzhegeshurufa\t这个输入法\nshurufahaixuyao\t输入法还需要\nhaixuyaojixuyouhua\t还需要继续优化\njixuyouhua\t继续优化\nfanyinggengkuai\t反应更快\nshurugengshunchang\t输入更顺畅\nzhendonggenggenshou\t振动更跟手\n'''
text = LEXICON.read_text(encoding='utf-8')
if '# V0.10 common chat phrase layer' not in text:
    LEXICON.write_text(text.rstrip() + extra + '\n', encoding='utf-8')

print('V0.10 ranking patch applied')
