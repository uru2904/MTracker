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
    seen, pdfs, pages = set(), set(), [BASE]
    for depth in range(2):
        nxt = []
        for url in pages:
            if url in seen: continue
            seen.add(url)
            try:
                html = requests.get(url, timeout=30).text
            except Exception as e:
                print("WARN", url, e); continue
            for href in re.findall(r'href=["\']([^"\']+)["\']', html, re.I):
                full = urljoin(url, href)
                if "argenfood" not in full: continue
                if full.lower().endswith(".pdf"): pdfs.add(full)
                elif re.search(r"\.(htm|html|php)$", full, re.I) or full.endswith("/"): nxt.append(full)
        pages = nxt
    for g in KNOWN:
        pdfs.add(f"{BASE}Tablas/Grupo/{g}.pdf")
    return sorted(p for p in pdfs if "/Tablas/Grupo/" in p)

def num(s):
    return float(s.replace(",", "."))

def debug(data, group):
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        page = pdf.pages[0]
        ws = page.extract_words()
        print(f"DEBUG {group}: {len(pdf.pages)} páginas, {len(ws)} palabras en la 1ra, ancho {page.width}")
        for w in ws[:120]:
            print("   W", repr(w["text"]), round(w["x0"]), round(w["x1"]), round(w["top"]))
        t = page.extract_text() or ""
        print("   TEXT", t[:1500].replace("\n", " | "))

def parse_pdf(data, group):
    foods = []
    if group == "Cereales": debug(data, group)
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for page in pdf.pages:
            words = page.extract_words(keep_blank_chars=False, use_text_flow=False)
            # Centros de columna según el encabezado de la página
            cols = {}
            for w in words:
                t = w["text"].lower()
                x = (w["x0"] + w["x1"]) / 2
                if "kcal" in t: cols.setdefault("kcal", x)
                elif t.startswith("prote"): cols.setdefault("p", x)
                elif t.startswith("grasa"): cols.setdefault("f", x)
                elif t.startswith("totales"): cols.setdefault("ct", x)
                elif t.startswith("disponible"): cols.setdefault("cd", x)
                elif t.startswith("agua"): cols.setdefault("agua", x)
                elif "(kj)" in t or t == "kj": cols.setdefault("kj", x)
            if not {"kcal", "p", "f"} <= cols.keys():
                continue
            header_bottom = max(w["bottom"] for w in words if w["text"].lower().startswith(("prote", "grasa")))
            first_num_x = min(v for v in cols.values()) - 25
            # Agrupar palabras por renglón
            lines = {}
            for w in words:
                if w["top"] <= header_bottom + 2: continue
                key = round(w["top"] / 3)
                lines.setdefault(key, []).append(w)
            for _, ws in sorted(lines.items()):
                ws.sort(key=lambda w: w["x0"])
                name_words = [w["text"] for w in ws if w["x1"] < first_num_x]
                nums = [w for w in ws if w["x0"] >= first_num_x and NUM.match(w["text"])]
                if not name_words or not nums: continue
                if NUM.match(name_words[0]): name_words = name_words[1:]  # Nº
                name = " ".join(name_words).strip(" ,")
                if len(name) < 3: continue
                vals = {}
                for w in nums:
                    x = (w["x0"] + w["x1"]) / 2
                    k = min(cols, key=lambda c: abs(cols[c] - x))
                    if abs(cols[k] - x) < 22: vals.setdefault(k, num(w["text"]))
                if not {"kcal", "p", "f"} <= vals.keys(): continue
                kcal, p, f = vals["kcal"], vals["p"], vals["f"]
                # Carbohidratos: el que mejor cierra la energía (4P + 4C + 9G)
                cands = [vals[c] for c in ("cd", "ct") if c in vals]
                if cands:
                    c = min(cands, key=lambda c: abs(4 * p + 4 * c + 9 * f - kcal))
                else:
                    c = max(0.0, (kcal - 4 * p - 9 * f) / 4)
                foods.append({"name": name, "kcal": round(kcal), "p": round(p, 1), "c": round(c, 1), "f": round(f, 1), "group": group})
    return foods

def main(out):
    all_foods = []
    for url in find_pdfs():
        group = url.rsplit("/", 1)[-1].rsplit(".", 1)[0]
        try:
            r = requests.get(url, timeout=60)
            if r.status_code != 200 or not r.content.startswith(b"%PDF"):
                print(f"SKIP {group}: {r.status_code}"); continue
            foods = parse_pdf(r.content, group)
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
