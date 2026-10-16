"""Rebuild web/saved-runs.json: one real, earlier Gemini decision per demo order, read from Firestore.

Run from the repo root:  python3 web/tools/build-saved-runs.py
"""
import json
import urllib.request

PROJECT = "returnwise-catalogue-2016"
CONFIG = "https://returnwise-catalogue-2016.web.app/__/firebase/init.json"
# The case shown for each demo order (chosen for a clean, representative decision).
PICK = {
    "LL-10021": "RC-F3F0B7CB",
    "LL-10022": "RC-1B246DDC",
    "LL-10023": "RC-66B0C579",
    "LL-10024": "RC-8CB4BEC9",
    "LL-10025": "RC-41C84112",
}
PHOTOS = {"LL-10022": "samples/coat-seam.svg"}


def value(v):
    kind, val = next(iter(v.items()))
    if kind == "mapValue":
        return {k: value(x) for k, x in val.get("fields", {}).items()}
    if kind == "arrayValue":
        return [value(x) for x in val.get("values", [])]
    if kind == "integerValue":
        return int(val)
    if kind == "nullValue":
        return None
    return val


key = json.load(urllib.request.urlopen(CONFIG))["apiKey"]
out = {}
for order, case in PICK.items():
    url = (f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)"
           f"/documents/returnCases/{case}?key={key}")
    doc = json.load(urllib.request.urlopen(url))
    c = {k: value(v) for k, v in doc["fields"].items()}
    if order in PHOTOS:
        c["photoUrl"] = PHOTOS[order]
    out[order] = c
with open("web/saved-runs.json", "w") as f:
    json.dump(out, f, indent=1, ensure_ascii=False, sort_keys=True)
    f.write("\n")
print("wrote", len(out), "saved runs")
