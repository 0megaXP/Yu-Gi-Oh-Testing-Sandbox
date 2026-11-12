import json, re, urllib.request, collections
UA={"User-Agent":"YGOSandbox-diag/1.0"}
cards=json.loads(urllib.request.urlopen(urllib.request.Request("https://db.ygoprodeck.com/api/v7/cardinfo.php",headers=UA),timeout=180).read())["data"]
eng={}
for line in open("diag/engine_names.tsv",encoding="utf-8"):
    i,n=line.rstrip("\n").split("\t",1); eng[int(i)]=n
norm=lambda s: re.sub(r"[^a-z0-9]","",s.lower())
mism=[]; missing=[]; ok=0; checked=0
for c in cards:
    if c.get("frameType") in ("skill","token"): continue
    ids={c["id"]}|{im["id"] for im in c.get("card_images",[])}
    tcg = "tcg_date" in c.get("misc_info",[{}])[0] if c.get("misc_info") else True
    found=False
    for i in ids:
        if i in eng:
            found=True; checked+=1
            if norm(eng[i])==norm(c["name"]): ok+=1
            else: mism.append((i,c["name"],eng[i]))
    if not found: missing.append((c["id"],c["name"]))
print("R| YGOPRODeck cards:",len(cards)," passcodes checked:",checked," same name:",ok," different name:",len(mism)," not in engine:",len(missing))
byname={norm(n):i for i,n in eng.items()}
rec=[(i,n,byname[norm(n)]) for i,n in missing if norm(n) in byname]
print("R| missing but present under another id (name check fixes these):",len(rec))
for r in rec: print("R| REC",r)
# Would a TCG name of a DIFF card point at some other engine card?
risky=[(i,a,b,byname[norm(a)]) for i,a,b in mism if norm(a) in byname and byname[norm(a)]!=i]
print("R| translation diffs whose TCG name is another engine card:",len(risky))
for r in risky: print("R| RISK",r)
