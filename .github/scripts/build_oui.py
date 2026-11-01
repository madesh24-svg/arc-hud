"""Convert Wireshark's manuf file into a compact gzip table: one 'AABBCC<TAB>Vendor name' per line."""
import gzip
import sys

src, dest = sys.argv[1], sys.argv[2]
rows = {}
with open(src, encoding="utf-8", errors="replace") as f:
    for line in f:
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.rstrip("\n").split("\t")
        prefix = parts[0].strip()
        if "/" in prefix:  # smaller (MA-M / MA-S) blocks are skipped; the 24-bit prefix covers most devices
            continue
        hexs = prefix.replace(":", "").replace("-", "").upper()
        if len(hexs) != 6:
            continue
        name = (parts[2] if len(parts) > 2 and parts[2].strip() else parts[1] if len(parts) > 1 else "").strip()
        if name:
            rows[hexs] = name.replace("\t", " ")
with gzip.open(dest, "wt", encoding="utf-8") as out:
    for key in sorted(rows):
        out.write(key + "\t" + rows[key] + "\n")
print(len(rows), "manufacturers written to", dest)
