#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
高频口语词表扩充管线（v10.3 追加：dict.json ~300 词 → ~1500 词）。

流程：
  读 app/src/main/assets/nce/dict.json（现有词，保留不覆盖，只增不改）
    → 词源：脚本内置 COCA 风格高频口语词池（优先），不足部分向智谱追讨（JSON 数组词表）
    → 逐批请求智谱（OpenAI 兼容，BASE_URL=https://open.bigmodel.cn/api/paas/v4，MODEL=glm-5.3-flash）
      生成 {word, phonetic, meaning, example, exampleZh}
    → 逐条校验（宁缺勿错），合并写回 app/src/main/assets/nce/dict.json

约定（与 gen_lesson_svgs.py / gen_syllables.py 一致）：
  - API key 只从环境变量 NCE_SVG_API_KEY 读取，严禁写进代码或任何文件
  - 断点续跑：进度缓存 tools/.dict_highfreq_progress.json（已生成词条跨次运行保留）
  - 并发 ≤ 4，单批失败重试 2 次
  - 只增不改：现有词条原样保留；新词去重（忽略大小写）；校验不过丢弃该词
  - 校验：word 匹配请求词、phonetic 以 "/" 开头、meaning 含中文、example/exampleZh 非空

用法：
  export NCE_SVG_API_KEY=<your-key>
  python tools/gen_dict_highfreq.py [--target 1500] [--batch-size 25] [--workers 4] [--dry-run]
"""

import argparse
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

# ---------- 常量 ----------

ROOT = Path(__file__).resolve().parent.parent
DICT_JSON = ROOT / "app" / "src" / "main" / "assets" / "nce" / "dict.json"
PROGRESS_FILE = ROOT / "tools" / ".dict_highfreq_progress.json"

BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
MODEL = "glm-5.3-flash"
MAX_WORKERS = 4
MAX_RETRIES = 2

ENTRY_SYSTEM_PROMPT = """You are an English-Chinese dictionary writer for a spoken-English learning app.
For each English word given, output an entry. Output ONLY a JSON array of objects:
[{"word": "<the word, lowercase>", "phonetic": "<American IPA, wrapped in slashes, e.g. /əˈbaʊt/>", "meaning": "<词性缩写. 简明中文释义（1~2个），如 prep. 关于；大约>", "example": "<one short natural spoken-English sentence using the word>", "exampleZh": "<例句的中文翻译>"}]
Requirements:
- meaning must be in Chinese with a part-of-speech abbreviation (n. / v. / adj. / adv. / prep. / pron. / conj. / art. / num. / interj.).
- example must be a short, everyday spoken sentence (5~10 words).
- Output nothing except the JSON array (no markdown fence, no explanation)."""

WORDLIST_SYSTEM_PROMPT = """You are a lexicographer. Output ONLY a JSON array of English words (lowercase, letters only).
The words must be high-frequency spoken English (COCA spoken-frequency style), useful for daily conversation.
Exclude all words listed by the user. Output exactly as many words as asked, no duplicates, no explanation."""

# 内置 COCA 风格高频口语词池（优先用；与内置 dict 现有词去重后取用）
BUILTIN_POOL = """
ability able about above accept accident account ache achieve across act action active actually
add address admit adult advance advantage adventure advice advise afford afraid after afternoon
again against age agent ago agree ahead aim air airplane airport alarm alive all allow almost
alone along already also although always amazing ambition amount ancient anger angle angry animal
ankle announce annoying another answer ant anxiety anxious any anybody anymore anyone anything
anyway apologize appear apple application apply appreciate approach April archive argue argument
arm army around arrange arrest arrive art article artist ashamed aside ask asleep aspect assist
assume attach attack attempt attend attention attitude attorney attract auction audience August
aunt author auto autumn available average avoid awake award aware away awful baby back background
backward bacon bad badly bag bake balance balcony ball balloon bamboo banana band bank bar barely
bark barrier base baseball basement basic basically basket basketball bat bath bathroom battery
battle beach bean bear beat beautiful beauty because become bed beef beer before beg begin
behavior behind belief believe bell belong below belt bench bend beneath benefit berry beside
best bet better between beyond bicycle big bill bind bird birth birthday biscuit bit bite bitter
black blade blame blanket bless blind block blood blow blue board boat body boil bold bomb bone
book boot border bore born borrow boss both bother bottle bottom bounce bowl box boy brain
brake branch brave bread break breakfast breath breathe breeze brick bride bridge brief bright
brilliant bring broad broken broom brother brown brush bubble bucket budget build building bulb
bull burn burst bury bus business busy butter butterfly button buy cabinet cage cake calendar
call calm camera camp campus can cancel cancer candle cap capable capital captain capture car
carbon card care career careful careless cargo carpet carrot carry cart case cash cast castle
cat catch category cause ceiling celebrate cell cent center central century ceremony certain
chain chair chalk challenge champion chance change channel chapter character charge charm chart
chase cheap check cheek cheer cheese chef chemical chest chicken chief child childhood chill
chimney chin chip chocolate choice choose chop church cigar circle circumstance citizen city
civil claim class classic clean clear clerk clever click client cliff climate climb clinic clip
clock close cloth clothes cloud club clue cluster coach coal coast coat code coffee coin cold
collapse collar collect college column comb combine come comedy comfort comfortable comic
command comment common communicate community company compare compete complain complete complex
composer computer concern concert conclude condition conduct confess confirm conflict confuse
congratulate connect consider consist constant contact contain content contest context continue
contract contrast contribute control conversation convince cook cookie cool copy core corn
corner correct cost cottage cotton cough could council count counter country couple courage
course court cousin cover cow crack crash cream create creature credit crew crime crisis
critic crop cross crowd crown cruel crush cry culture cup cupboard cure curious curl current
curtain curve custom customer cut cute cycle dad daily damage damp dance danger dangerous dare
dark dash data date daughter dawn day dead deaf deal dear death debate debt decade December
decide decision declare decorate decrease deep deer defeat defend delay delicate deliver demand
dental dentist depart department depend deposit describe desert deserve design desire desk
desperate despite dessert destroy detail detect determine develop device devil diagram dial
dialogue diamond diary dictation dictionary die differ difficult dig digital dinner direct
direction dirt disappear disappoint disaster discount discover discuss disease dish dismiss
disorder display distance distant divide dizzy doctor document dog doll dollar dolphin dome
donate done door double doubt dough down downtown dozen drag dragon drama draw drawer dream
dress drift drink drive driver drop drown drug drum dry duck due dull dumb during dust duty
eager eagle ear early earn earth ease easily east easy eat economy edge edition editor educate
education effect effort egg eight either elbow elder elect electric elegant element elephant
eleven eligible else elsewhere embarrass embrace emergency emotion emphasize employ empty
enable encounter encourage endless enemy energy engage engine engineer enjoy enough enter
entertain entire entrance envelope environment envy equal equipment error escape especially
essay essential establish estate estimate even evening event eventually ever every everybody
everyday everyone everything everywhere evidence evil exact exam examine example excellent
except exchange excite excuse exercise exhaust exhibit exist exit expand expect expensive
experience experiment expert explain explode explore export expose express extend extra
extreme eye fabric face fact factory fade fail faint fair faith fall false familiar family
famous fan fancy fantastic far fare farm fashion fast fat fate father fault favor fear feast
feather February fee feed feel fellow female fence festival fetch fever few fiber fiction
field fierce fifteen fifty fight figure file fill film filter final finally finance find fine
finger finish fire firm first fish fist fit five fix flag flame flash flat flavor fleet flesh
flexible flight float flood floor flour flow flower fly focus fold folk follow fond food fool
foot football forbid force forecast forehead foreign forest forever forget forgive fork form
formal former fortune forty forward fossil foster found fountain four fourth fraction fragile
frame free freeze frequent fresh friend fright frighten frog front frost frown fruit fry fuel
full fun function fund funeral funny fur furniture further future gain gallon game gang gap
garage garbage garden garlic gas gate gather gay gaze gear general generous gentle genuine
gesture ghost giant gift giant giggle ginger giraffe give glad glance glass glide globe glory
glove glow glue goal goat gold golden golf good goods goose govern government grab grace grade
gradual graduate grain grand grant grape grasp grass grateful grave gravity gray great greedy
green greet grid grief grocery ground group grow guard guess guest guide guilt guitar gun guy
gym habit hair half hall ham hamburger hammer hand handle handsome hang happen happiness happy
harbor hard hardly harm hat hate have hay headache heal health heap hear heart heat heaven
heavy hedge height hello helmet help hen herb here hero hesitate hide high hill hint hip hire
history hit hobby hockey hold hole holiday hollow holy home honest honey honor hook hope
horrible horse hospital host hot hotel hour house household how however huge human humble
humor hunger hungry hunt hurry hurt husband hut hydrogen ice idea ideal identify identity
idle ignore ill illegal illness image imagine imitate immediate impact import impress improve
impulse inch incident include income increase incredible indeed independent index indicate
indoor industry infant infect inform initial injure injury ink inner innocent innovation
insect inside insist inspire install instance instead institute instruct instrument insurance
intend interest internal international internet interpret interrupt interval interview into
introduce invent invest investigate invite involve iron island issue item ivory jacket jail
jam January jaw jazz jealous jeans jet jewel job jog join joke journal journey joy judge juice
July jump June junior junk just justice keen keep kettle key keyboard kick kid kill kind king
kiss kit kitchen kite knee kneel knife knit knock knot know knowledge lab label labor lack
ladder lady lake lamb lamp land landscape lane language lantern lap large laser last late
lately later latest laugh launch laundry lawyer lay layer lazy lead leader leaf league lean
leap learn least leather leave lecture left leg legal legend lend length lens less lesson
letter lettuce level liberty library license lid lie life lift light lightning like likely
limit limp line linen link lion lip liquid list listen literature litter little live lively
liver living load loaf loan local locate lock lodge log logic lonely long look loop loose
lord lose loss lot loud love lovely low lower loyal luck lucky luggage lump lunch lung luxury
machine mad magazine magic mail main maintain major make male mall man manage manner
manufacture many map marathon marble march margin mark market marriage marry mass master
match mate material math matter mature maximum may maybe mayor meal mean meaning measure meat
mechanic medal media medical medicine medium meet melt member memory mental mention menu
merchandise mercy mere merry message metal method microwave mid middle midnight might mild
mile milk mill mind mine mineral minimum minor minus minute miracle mirror miss missile mist
mistake mix mixture moan mobile model modern modest moment Monday money monitor monkey month
mood moon mop moral more morning mosquito most mother motion motor mount mountain mouse mouth
move movie much mud multiply murder muscle museum mushroom music must myself mysterious
mystery nail naive name nap narrow nation native nature naughty naval navy near neat
necessary neck need negative neglect neighbor neither nephew nerve nervous nest net network
neutral never nevertheless new news next nice nickname niece night nine noble nobody nod
noise none nonsense noodle noon nor normal north nose not note nothing notice noun November
now nowhere nuclear number nurse nut oak obey object observe obtain obvious occasion occupy
occur ocean October odd odor offer office officer official often oh oil okay old olive omit
once one onion online only onto open opera operate opinion oppose opposite option orange
orchestra order ordinary organ origin original other otherwise ought ounce our outdoor outer
outfit outlet outline output outside outstanding oval oven over overcome overlook owe own
owner oxygen pace pack package pad page pain painful paint pair palace pale palm pan
panda panel panic pants paper parade paragraph parallel parcel pardon parent park parrot
part particular partner party pass passage passenger passion passport past paste pastry path
patient patrol pattern pause pave payment peace peaceful peach peak peanut pear peasant pen
pencil people pepper percent perfect perform perhaps period permit person personal pet
petrol phase phenomenon philosophy phone photo photograph phrase physical piano pick picnic
picture pie piece pierce pig pigeon pile pilot pin pinch pink pipe pity pizza place plague
plain plan plane planet plant plastic plate platform play player plaza pleasant please
pleasure plenty plot plug plus pocket poem poet point poison poke polar pole police policy
polish polite political pollution pond pool poor pop popular population porch pork port
portion portrait position positive possess possible post potato potential pound pour poverty
powder power practice praise pray preach precious prefer pregnant prepare presence present
preserve president press pressure pretend pretty prevent previous price pride priest primary
prime prince princess principal principle print prior prison private privilege prize probable
problem procedure proceed process produce product profession professor profit program progress
prohibit project promise promote prompt proof proper property propose protect proud prove
provide public pudding pull pulse pump punch pupil purchase pure purple purpose purse pursue
push put puzzle qualify quality quantity quarrel quarter queen question queue quick quiet
quilt quit quite quiz quote rabbit race radio rag rail railway rain rainbow raise rally
random range rank rapid rare rat rate rather ratio raw ray reach react read ready real
realistic realize really reason rebel recall receipt receive recent recipe recite reckon
recognize recommend record recover recruit rectangle red reduce refer reflect reform refrain
refuse regard region register regret regular reject relax release relevant relief relieve
religion rely remain remark remind remote remove rent repair repeat replace reply report
represent request require rescue research resemble reserve resign resist resolve resort
resource respect respond responsible rest restaurant result resume retail retain retire
return reveal revenue review revise revolt reward rhythm rice rich rid ride ring riot ripe
rise risk rival river road roast rob robot rock rod roll roof room root rope rose rotten rough
round route routine row royal rubber rubbish rude rug ruin rule rumor run rural rush rust
sacred sacrifice sad saddle safe safety sage salad salary sale salt same sample sanction sand
satellite satisfaction satisfy Saturday sausage savage save saw say scale scan scare scatter
scenery schedule scheme scholar school science scissors scold scope score scout scratch
scream screen screw script sea seal search season seat second secret section secure see seed
seek seem segment seize seldom select self sell semester send senior sense sentence separate
September sequence sergeant serious sermon servant serve service session set settle several
severe sew shade shadow shake shall shallow shame shape share shark sharp shave she shed
sheep sheet shelf shell shelter shift shine ship shirt shiver shock shoe shoot shop shore
short shortage shoulder shout shove show shower shrimp shrink shrug shut shuttle shy sick
side sight sign signal silence silent silk silly silver similar simple simply sin since
sincere sing single sink sir siren sit site situation sixteen sixty size ski skill skin skip
skirt sky slave sleep sleeve slice slide slight slip slippery slogan slope slow small smart
smell smile smoke smooth snake sneeze sniff snow soak soap soccer social society sock sofa
soft soil solar soldier solid solve some somebody somehow someone something sometimes
somewhere son song soon sorrow sorry sort soul sound soup sour source south souvenir space
spare spade speak special species speech speed spell spend sphere spice spider spill spin
spirit spit splash split spoil spoke spoon sport spot spouse spray spread spring sprout
square squeeze stable stadium staff stage stain stair stake stale stamp stand star stare
start starve state statement station statue status stay steady steak steal steam steel steep
steer stem step stereo stern stew stick stiff still sting stir stitch stock stomach stone
stool stop store storm story stove straight strain strange stranger strap strategy straw
stream street strength stress stretch strict strike string strip stroke strong structure
struggle student studio study stuff stupid style subject submit subscribe substance
substitute subtle suburb succeed success such suck sudden suffer sufficient sugar suggest
suicide suitable suitcase sum summer summon Sunday sunset sunshine super superb supermarket
supper supply support suppose sure surface surgeon surprise surround survey survive suspect
swallow swamp swan swap swear sweat sweater sweep sweet swell swift swim swing switch sword
symbol sympathy symptom syndrome system table tablet tackle tactic tailor take tale talent
talk tall tank tap tape target task taste tattoo taxi tea teach team tear tease technique
technology tedious teeth telephone telescope television tell temper temperature temple
temporary ten tend tender tennis tense tent term terrible territory terror test text than
thank that thaw theater theft their them theme then theory there therefore thermal these
thick thief thin thing think third thirst thirteen thirty this thorn thorough those though
thought thousand thread threat throat throw thumb thunder ticket tidy tie tiger tight till
timber time tiny tip tired tissue title to toast tobacco today toe together toilet tomato
tomorrow tone tongue tonight too tool tooth top topic topple torch total touch tough tour
tourist toward towel tower town toy trace track trade tradition traffic tragedy trail train
trait transfer transform translate transport trap travel tray treasure treat treatment tree
tremble trend trial triangle tribe trick trifle trigger trim trip triple triumph trolley
troop trophy tropical trouble trousers truck true truly trumpet trunk trust truth tube tug
tune tunnel turkey turn turtle twelve twenty twice twin twist two type typical tyrant ugly
ultimate umbrella unable uncle under underline understand undo uneasy unemployed unexpected
unfair unfold uniform union unique unit unite universal universe unknown unless unlike
unlock unusual until up update upon upper upset upstairs urban urge usage useful usual
utensil vacant vacation vacuum vague vain valid valley valuable value van vanilla vary vast
vegetable vehicle veil vein velvet vendor venture venue verb verbatim verdict verse version
vertical very vessel veteran veto via vibrate vice victim victory video view vigorous village
vinegar violate violence violin virtue virus visa visible vision visit visual vital vitamin
vivid vocal voice volcano volleyball volume voluntary vote vowel voyage wage wagon waist wait
wake walk wall wander want war warm warn wash waste watch water waterfall wave wax way weak
wealth weapon wear weather weave website wedding Wednesday weed week weep weigh weight weird
welcome welfare well west wet whale wheat wheel when where whether while whisper whistle
white whole who whoever whole widen widow width wife wild will willing win wind window wine
wing wipe wire wisdom wise wish wit with withdraw witness wolf woman wonder wood wool word
work workshop world worm worried worse worship worth would wound wrap wreck wrist write
wrong yard yawn year yell yellow yes yesterday yet yield you young your youth zebra zero
zone zoo
""".split()


def load_existing() -> tuple[list[dict], set[str]]:
    """读现有 dict.json：词条原样保留 + 已有词集合（忽略大小写）。"""
    try:
        entries = json.loads(DICT_JSON.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        print(f"错误：读取 {DICT_JSON} 失败: {e}", file=sys.stderr)
        sys.exit(1)
    words = {e.get("word", "").strip().lower() for e in entries if isinstance(e, dict)}
    return entries, words


def load_progress() -> dict[str, dict]:
    if PROGRESS_FILE.exists():
        try:
            return json.loads(PROGRESS_FILE.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            return {}
    return {}


def save_progress(progress: dict[str, dict]) -> None:
    PROGRESS_FILE.write_text(
        json.dumps(progress, ensure_ascii=False, indent=1), encoding="utf-8"
    )


def call_zhipu(system: str, user: str, api_key: str, timeout: int = 180) -> str:
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": 0.3,
    }
    req = urllib.request.Request(
        url=f"{BASE_URL}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    content = body["choices"][0]["message"]["content"].strip()
    if content.startswith("```"):
        content = content.removeprefix("```json").removeprefix("```")
        if content.endswith("```"):
            content = content.removesuffix("```")
    return content.strip()


def fetch_entries(batch: list[str], api_key: str) -> dict[str, dict]:
    """一批词 → 词典条目（重试 2 次 + 硬校验，不过的丢：宁缺勿错）。"""
    last_err = ""
    for attempt in range(1 + MAX_RETRIES):
        try:
            user = json.dumps(batch, ensure_ascii=False)
            raw = json.loads(call_zhipu(ENTRY_SYSTEM_PROMPT, user, api_key))
            ok: dict[str, dict] = {}
            wanted = set(batch)
            for item in raw if isinstance(raw, list) else []:
                if not isinstance(item, dict):
                    continue
                w = str(item.get("word", "")).strip().lower()
                phon = str(item.get("phonetic", "")).strip()
                meaning = str(item.get("meaning", "")).strip()
                example = str(item.get("example", "")).strip()
                example_zh = str(item.get("exampleZh", "")).strip()
                # 硬校验：word 命中请求、音标斜杠、释义含中文、例句非空
                if w not in wanted:
                    continue
                if not phon.startswith("/"):
                    continue
                if not re.search(r"[\u4e00-\u9fff]", meaning):
                    continue
                if not example or not example_zh:
                    continue
                ok[w] = {
                    "word": w,
                    "phonetic": phon,
                    "meaning": meaning,
                    "example": example,
                    "exampleZh": example_zh,
                }
            return ok
        except Exception as e:  # noqa: BLE001
            last_err = f"{type(e).__name__}: {e}"
            time.sleep(2 * (attempt + 1))
    print(f"  批失败（{len(batch)} 词）：{last_err}", file=sys.stderr)
    return {}


def fetch_wordlist(exclude: set[str], count: int, api_key: str) -> list[str]:
    """内置池不够时向智谱追讨高频词（多轮，排除已有）。"""
    got: list[str] = []
    for _ in range(5):  # 最多追讨 5 轮，防死循环
        need = count - len(got)
        if need <= 0:
            break
        sample = sorted(exclude)[:400]  # 排除表截断防爆 prompt
        try:
            raw = json.loads(
                call_zhipu(
                    WORDLIST_SYSTEM_PROMPT,
                    json.dumps({"count": need, "exclude": sample}, ensure_ascii=False),
                    api_key,
                )
            )
        except Exception as e:  # noqa: BLE001
            print(f"  词表追讨失败: {e}", file=sys.stderr)
            break
        for w in raw if isinstance(raw, list) else []:
            w = str(w).strip().lower()
            if re.fullmatch(r"[a-z]+", w) and len(w) >= 2 and w not in exclude and w not in got:
                got.append(w)
    return got


def main() -> int:
    parser = argparse.ArgumentParser(description="高频口语词表扩充 dict.json")
    parser.add_argument("--target", type=int, default=1500, help="目标总词数（默认 1500）")
    parser.add_argument("--batch-size", type=int, default=25, help="每次请求的词数（默认 25）")
    parser.add_argument("--workers", type=int, default=MAX_WORKERS, help="并发数（默认 4）")
    parser.add_argument("--dry-run", action="store_true", help="只输出候选词表不请求条目")
    args = parser.parse_args()

    api_key = os.environ.get("NCE_SVG_API_KEY", "").strip()
    if not api_key:
        print("错误：未设置环境变量 NCE_SVG_API_KEY（严禁把 key 写进任何文件）", file=sys.stderr)
        return 1

    existing_entries, existing_words = load_existing()
    print(f"dict.json 现有 {len(existing_entries)} 词（原样保留，只增不改）")
    target_new = max(0, args.target - len(existing_entries))
    print(f"目标总词数 {args.target}，需新增 {target_new} 词")

    # 词源：内置池优先 → 不足向智谱追讨
    pool = [w for w in dict.fromkeys(BUILTIN_POOL) if w not in existing_words]
    print(f"内置高频词池去重后可用 {len(pool)} 词")
    candidates = pool[:target_new]
    if len(candidates) < target_new:
        extra = fetch_wordlist(existing_words | set(candidates), target_new - len(candidates), api_key)
        print(f"智谱追讨补充 {len(extra)} 词")
        candidates += extra

    # 断点续跑：进度缓存里已有的词条直接复用
    progress = load_progress()
    todo = [w for w in candidates if w not in progress]
    print(f"待生成条目 {len(todo)} 词（进度缓存已含 {len(progress)} 条）")
    if args.dry_run:
        print("候选词表：", " ".join(candidates[:100]), "…" if len(candidates) > 100 else "")
        return 0
    if not todo:
        merge_and_write(existing_entries, progress, args.target)
        return 0

    batches = [todo[i : i + args.batch_size] for i in range(0, len(todo), args.batch_size)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool_ex:
        futures = {pool_ex.submit(fetch_entries, b, api_key): b for b in batches}
        done = 0
        for fut in concurrent.futures.as_completed(futures):
            got = fut.result()
            progress.update(got)
            save_progress(progress)  # 每批落盘：断点续跑
            done += 1
            print(f"  进度 {done}/{len(batches)} 批，累计有效 {len(progress)} 条")

    merge_and_write(existing_entries, progress, args.target)
    return 0


def merge_and_write(existing_entries: list[dict], progress: dict[str, dict], target: int) -> None:
    """合并写回 dict.json：现有词条原样在前，新词条按字母序追加（只增不改，去重）。"""
    existing_entries, existing_words = load_existing()  # 重读防运行中被改
    added = 0
    for w in sorted(progress):
        if w in existing_words:
            continue
        entry = progress[w]
        if isinstance(entry, dict) and entry.get("word") == w:
            existing_entries.append(entry)
            existing_words.add(w)
            added += 1
        if len(existing_entries) >= target:
            break
    DICT_JSON.write_text(
        json.dumps(existing_entries, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    print(f"完成：dict.json 现有 {len(existing_entries)} 词，本次新增 {added} 条（校验不过/重复已丢弃）")


if __name__ == "__main__":
    sys.exit(main())
