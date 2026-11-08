import json, re, urllib.request
UA={"User-Agent":"YGOSandbox-diag/1.0"}
cards=json.loads(urllib.request.urlopen(urllib.request.Request("https://db.ygoprodeck.com/api/v7/cardinfo.php",headers=UA),timeout=120).read())["data"]
by={c["name"].lower():c for c in cards}
H1=re.compile(r"you can only (use|activate|special summon|normal summon|apply)[^.]*(once per turn|per turn)")
H2=re.compile(r"you can only use (each|1|one)[^.]*per turn")
def opt(c):
    t=c["desc"].lower()
    if H1.search(t) or H2.search(t): return "HARD"
    return "SOFT" if "once per turn" in t else "NONE"
import collections
cnt=collections.Counter(opt(c) for c in cards); print("O| totals", dict(cnt))
for n in ["Ash Blossom & Joyous Spring","Maxx \"C\"","Effect Veiler","Ghost Belle & Haunted Mansion","Infinite Impermanence","Called by the Grave","Lightning Storm","Evenly Matched","Triple Tactics Talent","Pot of Prosperity","Nibiru, the Primal Being","Branded Fusion","Fallen of Albaz","Aluber the Jester of Despia","Branded in Red","Bystial Druiswurm","Sky Striker Ace - Raye","Skill Drain","Mystic Mine","Snake-Eye Ash","Fiendsmith Engraver","Mulcharmy Purulia","Droll & Lock Bird","Dimension Shifter","Kashtira Fenrir","Mr. Volcano","Gameciel, the Sea Turtle Kaiju","Harpie's Feather Duster","Raigeki","Pot of Desires"]:
    c=by.get(n.lower())
    if not c: print("O|",n,"NOT FOUND"); continue
    t=c["desc"].lower(); m=re.search(r"(you can only[^.]*\.|once per turn)",t)
    print(f"O| {n:32s} {opt(c):5s} | {m.group(0)[:90] if m else '-'}")
# hard-looking phrases missed
miss=[c["name"] for c in cards if opt(c)!="HARD" and "you can only" in c["desc"].lower() and "per turn" in c["desc"].lower()]
print("O| possible misses:",len(miss), miss[:15])
