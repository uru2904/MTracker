"""Descarga las tablas de ARGENFOOD (Universidad Nacional de Luján) y genera assets/argenfood.json.

Se ejecuta en GitHub Actions antes de compilar. Los datos no se guardan en el repo.
Cada alimento: nombre, kcal, proteína, grasas y carbohidratos cada 100 g de porción comestible.
"""
import json, re, sys, io, statistics
from urllib.parse import urljoin
import requests, pdfplumber

BASE = "https://www.argenfood.unlu.edu.ar/"
KNOWN = ["Cereales", "Vegetales", "Pescados", "Carnes", "Leches", "Huevos", "Frutas",
         "Azucares", "Grasas", "Bebidas", "Varios", "Comidas"]
NUM = re.compile(r"^-?\d+(?:[.,]\d+)?$")

def find_pdfs():
    for extra in ("Tablas/Tabla.htm",):
        try:
            h = requests.get(BASE + extra, timeout=30)
            print("INDEX", extra, h.status_code, " ".join(re.findall(r'(?:href|src)=["\']([^"\']+)["\']', h.text, re.I)))
        except Exception as e:
            print("INDEX ERROR", extra, e)
    seen, pdfs, pages = set(), set(), [BASE]
    for depth in range(6):
        nxt = []
        for url in pages:
            if url in seen: continue
            seen.add(url)
            try:
                html = requests.get(url, timeout=30).text
            except Exception as e:
                print("WARN", url, e); continue
            for href in re.findall(r'(?:href|src)=["\']([^"\']+)["\']', html, re.I):
                full = urljoin(url, href)
                if "argenfood" not in full.lower(): continue
                if full.lower().endswith(".pdf"): pdfs.add(full)
                elif re.search(r"\.(htm|html|php)$", full, re.I) or full.endswith("/"): nxt.append(full)
        pages = nxt
    for g in KNOWN:
        pdfs.add(f"{BASE}Tablas/Grupo/{g}.pdf")
    found = sorted(pdfs)
    print("PDFS", found)
    return found

def num(s):
    return float(s.replace(",", "."))

TAGS = {"PROTCNT": "p", "FAT": "f", "CHOCDF": "ct", "CHO": "ct", "CHOAVLDF": "cd", "WATER": "agua", "FIBTG": "fibra"}

def parse_pdf(data, group):
    """Usa la fila de códigos INFOODS (<ENERC>, <PROTCNT>, <FAT>, ...) para ubicar cada columna.
    Los números están alineados a la derecha, así que se comparan por el borde derecho."""
    foods = []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for page in pdf.pages:
            words = page.extract_words()
            cols, tag_bottom, enerc = {}, None, 0
            for w in words:
                t = w["text"].strip()
                if not t.startswith(("<", ">")): continue
                tags = re.findall(r"[A-Z]+", t)
                n = len(tags)
                for k, m in enumerate(tags):
                    # Palabras con dos códigos pegados (<WATER><PROTCNT>): se reparte el ancho.
                    x1 = w["x0"] + (w["x1"] - w["x0"]) * (k + 1) / n
                    key = None
                    if m == "ENERC":
                        key = "kj" if enerc == 0 else "kcal"; enerc += 1
                    elif m in TAGS:
                        key = TAGS[m]
                    if key and key not in cols:
                        cols[key] = x1
                        tag_bottom = max(tag_bottom or 0, w["bottom"])
            if not {"kcal", "f"} <= cols.keys():
                continue
            name_limit = cols.get("kj", cols["kcal"]) - 30
            lines = {}
            for w in words:
                if w["top"] <= tag_bottom + 15: continue  # salta la fila "26*1 2*1 ..."
                lines.setdefault(round(w["top"] / 3), []).append(w)
            for _, ws in sorted(lines.items()):
                ws.sort(key=lambda w: w["x0"])
                name_words = [w["text"] for w in ws if w["x1"] < name_limit]
                nums = [w for w in ws if w["x0"] >= name_limit - 10 and NUM.match(w["text"])]
                if not name_words or len(nums) < 3: continue
                if NUM.match(name_words[0]): name_words = name_words[1:]  # Nº
                name = re.sub(r"\s+,", ",", " ".join(name_words)).strip(" ,")
                if len(name) < 3: continue
                vals = {}
                for w in nums:
                    k = min(cols, key=lambda c: abs(cols[c] - w["x1"]))
                    if abs(cols[k] - w["x1"]) <= 20: vals.setdefault(k, num(w["text"]))
                if "kcal" not in vals: continue
                kcal, p, f = vals["kcal"], vals.get("p", 0.0), vals.get("f", 0.0)
                # Carbohidratos disponibles si están; si no, totales menos fibra; si no, totales.
                if "cd" in vals: c = vals["cd"]
                elif "ct" in vals: c = max(0.0, vals["ct"] - vals.get("fibra", 0.0))
                else: c = max(0.0, (kcal - 4 * p - 9 * f) / 4)
                foods.append({"name": name, "kcal": round(kcal), "p": round(p, 1), "c": round(c, 1), "f": round(f, 1), "group": group})
    return foods

def debug_tags(data, group):
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        ws = pdf.pages[0].extract_words()
        tags = [(w["text"], round(w["x0"]), round(w["x1"]), round(w["top"])) for w in ws if "<" in w["text"] or ">" in w["text"]]
        print(f"TAGS {group}", tags[:30])
        rows = [w for w in ws if w["top"] > 100][:40]
        print(f"ROWS {group}", [(w["text"], round(w["x0"]), round(w["x1"]), round(w["top"])) for w in rows])

def main(out):
    all_foods = []
    for url in find_pdfs():
        group = requests.utils.unquote(url.rsplit("/", 1)[-1].rsplit(".", 1)[0])
        try:
            r = requests.get(url, timeout=60)
            if r.status_code != 200 or not r.content.startswith(b"%PDF"):
                print(f"SKIP {group}: {r.status_code}"); continue
            foods = parse_pdf(r.content, group)
            if not foods: debug_tags(r.content, group)
            bad = [x for x in foods if abs(4 * x["p"] + 4 * x["c"] + 9 * x["f"] - x["kcal"]) > max(25, 0.25 * x["kcal"])]
            for x in bad[:3]: print("    NO CIERRA", x)
        except Exception as e:
            print(f"ERROR {group}: {e}"); continue
        ok = [x for x in foods if abs(4 * x["p"] + 4 * x["c"] + 9 * x["f"] - x["kcal"]) <= max(25, 0.25 * x["kcal"])]
        print(f"{group}: {len(foods)} alimentos, {len(ok)} cierran la energía")
        for x in foods[:4]: print("   ", x)
        all_foods.extend(foods)
    # sin duplicados exactos
    uniq = {(x["name"].lower(), x["kcal"]): x for x in all_foods}
    data = sorted(uniq.values(), key=lambda x: x["name"])
    print(f"TOTAL {len(data)}")
    if len(data) < 50:
        print("Muy pocos alimentos: no se genera el archivo"); sys.exit(0)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False)

if __name__ == "__main__":
    main(sys.argv[1])
