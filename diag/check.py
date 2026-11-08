import json, urllib.request
UA={"User-Agent":"YGOSandbox-diag/1.0"}
cards=json.loads(urllib.request.urlopen(urllib.request.Request("https://db.ygoprodeck.com/api/v7/cardinfo.php",headers=UA),timeout=120).read())["data"]
by={c["name"].lower():c for c in cards}
def guess(c):
    fr=c.get("frameType","")
    if any(fr.startswith(x) for x in ("fusion","synchro","xyz","link")): return ["(extra)"]
    t=c.get("desc","").lower(); typ=c.get("type",""); race=c.get("race","")
    mon="Monster" in typ; spell="Spell" in typ; trap="Trap" in typ; cont=race=="Continuous"; qp=race=="Quick-Play"
    fh=any(x in t for x in ["discard this card","send this card from your hand to the gy","reveal this card in your hand","banish this card from your hand"])
    opp=any(x in t for x in ["(quick effect)","during either player's turn","during your opponent's turn","when your opponent","if your opponent"])
    r=[]
    if mon and fh and opp: r.append("HAND_TRAP")
    if trap and "activate this card from your hand" in t: r.append("HAND_TRAP")
    br=any(x in t for x in ["destroy all","banish all","return all","all face-up monsters your opponent controls","to your opponent's field","your opponent controls more","shuffle all","monsters your opponent controls","cards your opponent controls"]) and any(x in t for x in ["destroy","banish","negate","shuffle","return","tribute"])
    if br and not cont: r.append("BREAKER")
    rm=any(x in t for x in ["negate","destroy","banish","return it to the hand"])
    if (trap and not cont and rm) or (qp and "negate" in t): r.append("INTERRUPTION")
    if (cont and any(x in t for x in ["cannot","neither player","negate"])) or (mon and any(x in t for x in ["your opponent cannot","neither player can"]) and not fh): r.append("ENDER")
    if not r:
        s=("add 1" in t or "add up to" in t) and "from your deck" in t
        sd="special summon" in t and "from your deck" in t
        fd="fusion summon" in t and "deck" in t
        ns=(not mon) or "cannot be normal summoned" not in t
        ss="special summon this card" in t
        if (s or sd or fd) and ns and (mon or spell): r.append("STARTER")
        elif mon and ss: r.append("EXTENDER")
        elif mon and "cannot be normal summoned" in t: r.append("BRICK")
    return r
for n in ["Ash Blossom & Joyous Spring","Maxx \"C\"","Effect Veiler","Ghost Belle & Haunted Mansion","Infinite Impermanence","Called by the Grave","Lightning Storm","Evenly Matched","Dark Ruler No More","Harpie's Feather Duster","Forbidden Droplet","Triple Tactics Talent","Pot of Prosperity","Nibiru, the Primal Being","Droll & Lock Bird","Branded Fusion","Fallen of Albaz","Aluber the Jester of Despia","Ecclesia, the Exiled","Branded in Red","Despian Tragedy","Bystial Druiswurm","Bystial Magnamhut","Kurikara Divincarnate","Gameciel, the Sea Turtle Kaiju","Super Polymerization","Skill Drain","There Can Be Only One","Sky Striker Mobilize - Engage!","Sky Striker Ace - Raye","Snake-Eye Ash","Fiendsmith Engraver","Mulcharmy Purulia","Dimension Shifter","Anti-Spell Fragrance","Blue-Eyes White Dragon","Albion the Shrouded Dragon","The Fallen & The Virtuous","Guiding Quem, the Virtuous","Tri-Brigade Mercourier"]:
    c=by.get(n.lower()); print(f"G| {n:40s} {guess(c) if c else 'NOT FOUND'}")
