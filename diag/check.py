import json, urllib.request
UA={"User-Agent":"YGOSandbox-diag/1.0"}
d=json.loads(urllib.request.urlopen(urllib.request.Request("https://db.ygoprodeck.com/api/v7/cardinfo.php?archetype=Angelechy",headers=UA),timeout=60).read())["data"]
for c in d:
    print("A|", c["id"], "|", c["name"], "|", c["desc"][:160].replace("\n"," "))
