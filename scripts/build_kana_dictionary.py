#!/usr/bin/env python3
"""Build the kana word-boundary index shipped as host-applemusic/src/main/assets/kana_dict.bin.

The index answers one question only: "is this kana string a Japanese word (per IPADIC)?".
It is a minimised DAWG over folded *kana surfaces* (IPADIC entries whose surface is written
entirely in kana), which is what the lyrics containers contain; kanji entries are deliberately
left out because the rule layer already proves the kanji/kana boundaries.

    python3 scripts/build_kana_dictionary.py              # download IPADIC, rebuild the asset
    python3 scripts/build_kana_dictionary.py --check      # rebuild in memory, compare with the asset
    python3 scripts/build_kana_dictionary.py --self-test  # folding + lookup probes only

The committed asset is reproducible: every input byte is pinned (URL + SHA-256) and the key set
is sorted, so a rebuild differs only in the built= header line unless the dictionary changed.
--check rebuilds with the stored timestamp, so it is a byte-for-byte comparison.

File layout (all integers big-endian)::

    magic         4 bytes  b"KND1"
    formatVersion u16      1
    metaLength    u16      byte length of the metadata block
    metadata      metaLength bytes, UTF-8, one "key=value" per line, keys:
                           source, source_sha256, built, entries, alphabet, fold, generator
    stateCount    u32
    edgeCount     u32
    states        stateCount x 10 bytes: u8 flags, u8 reserved, u16 childCount,
                           u32 childStart, i16 cost (0 in v1: costs are not shipped yet)
    edges         edgeCount x 5 bytes: u8 label, u32 targetState

label indexes the metadata alphabet string (the folded characters that actually occur in keys),
so a lookup only needs the same alphabet order. Each state's edges are sorted by label, which
lets the reader binary-search a child instead of scanning it.

Folding rules (identical to KanaDictionary.foldKana on the Kotlin side):

  * half-width katakana U+FF66..U+FF9D are mapped to their full-width forms;
  * a half-width dakuten U+FF9E / handakuten U+FF9F combines with the character before it;
  * full-width katakana U+30A1..U+30F6 are mapped to hiragana by subtracting 0x60;
  * the long-vowel mark (U+30FC, half-width U+FF70) stays U+30FC in both scripts;
  * everything else (kanji, latin, punctuation, digits) is left untouched and simply never
    matches, because the index holds kana keys only.

IPADIC 2.7.0 is copyright the Nara Institute of Science and Technology (NAIST) and ICOT; the
package's COPYING file is copied next to the asset as licenses/IPADIC-COPYING.txt.
"""

from __future__ import annotations

import argparse
import datetime
import glob
import hashlib
import io
import os
import struct
import sys
import tarfile
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FORMAT_VERSION = 1
MAGIC = b"KND1"
GENERATOR_VERSION = "1"

SOURCE_LABEL = "ipadic-2.7.0-20070801"
IPADIC_URL = (
    "http://archive.ubuntu.com/ubuntu/pool/main/m/mecab-ipadic/"
    "mecab-ipadic_2.7.0-20070801+main.orig.tar.gz"
)
IPADIC_SHA256 = "b62f527d881c504576baed9c6ef6561554658b175ce6ae0096a60307e49e3523"

DEFAULT_ASSETS = os.path.join(ROOT, "host-applemusic/src/main/assets")
ASSET_NAME = "kana_dict.bin"
LICENSE_NAME = "IPADIC-COPYING.txt"

# ---------------------------------------------------------------------------------------------
# Folding (mirror of KanaDictionary.foldKana)
# ---------------------------------------------------------------------------------------------

HALFWIDTH_TO_FULLWIDTH = {
    "\uff61": "\u3002", "\uff62": "\u300c", "\uff63": "\u300d", "\uff64": "\u3001",
    "\uff65": "\u30fb", "\uff66": "\u30f2", "\uff67": "\u30a1", "\uff68": "\u30a3",
    "\uff69": "\u30a5", "\uff6a": "\u30a7", "\uff6b": "\u30a9", "\uff6c": "\u30e3",
    "\uff6d": "\u30e5", "\uff6e": "\u30e7", "\uff6f": "\u30c3", "\uff70": "\u30fc",
    "\uff71": "\u30a2", "\uff72": "\u30a4", "\uff73": "\u30a6", "\uff74": "\u30a8",
    "\uff75": "\u30aa", "\uff76": "\u30ab", "\uff77": "\u30ad", "\uff78": "\u30af",
    "\uff79": "\u30b1", "\uff7a": "\u30b3", "\uff7b": "\u30b5", "\uff7c": "\u30b7",
    "\uff7d": "\u30b9", "\uff7e": "\u30bb", "\uff7f": "\u30bd", "\uff80": "\u30bf",
    "\uff81": "\u30c1", "\uff82": "\u30c4", "\uff83": "\u30c6", "\uff84": "\u30c8",
    "\uff85": "\u30ca", "\uff86": "\u30cb", "\uff87": "\u30cc", "\uff88": "\u30cd",
    "\uff89": "\u30ce", "\uff8a": "\u30cf", "\uff8b": "\u30d2", "\uff8c": "\u30d5",
    "\uff8d": "\u30d8", "\uff8e": "\u30db", "\uff8f": "\u30de", "\uff90": "\u30df",
    "\uff91": "\u30e0", "\uff92": "\u30e1", "\uff93": "\u30e2", "\uff94": "\u30e4",
    "\uff95": "\u30e6", "\uff96": "\u30e8", "\uff97": "\u30e9", "\uff98": "\u30ea",
    "\uff99": "\u30eb", "\uff9a": "\u30ec", "\uff9b": "\u30ed", "\uff9c": "\u30ef",
    "\uff9d": "\u30f3",
}

DAKUTEN = {
    "\u30ab": "\u30ac", "\u30ad": "\u30ae", "\u30af": "\u30b0", "\u30b1": "\u30b2",
    "\u30b3": "\u30b4", "\u30b5": "\u30b6", "\u30b7": "\u30b8", "\u30b9": "\u30ba",
    "\u30bb": "\u30bc", "\u30bd": "\u30be", "\u30bf": "\u30c0", "\u30c1": "\u30c2",
    "\u30c4": "\u30c5", "\u30c6": "\u30c7", "\u30c8": "\u30c9", "\u30cf": "\u30d0",
    "\u30d2": "\u30d3", "\u30d5": "\u30d6", "\u30d8": "\u30d9", "\u30db": "\u30dc",
    "\u30a6": "\u30f4", "\u30ef": "\u30f7", "\u30f2": "\u30fa",
}

HANDAKUTEN = {
    "\u30cf": "\u30d1", "\u30d2": "\u30d4", "\u30d5": "\u30d7", "\u30d8": "\u30da",
    "\u30db": "\u30dd",
}


def fold_kana(text: str) -> str:
    """Fold kana to the index's canonical form (see module docstring)."""
    out: list[str] = []
    for ch in text:
        code = ord(ch)
        if 0xFF66 <= code <= 0xFF9D:
            out.append(HALFWIDTH_TO_FULLWIDTH[ch])
            continue
        if ch == "\uff9e":
            if out and out[-1] in DAKUTEN:
                out[-1] = DAKUTEN[out[-1]]
            continue
        if ch == "\uff9f":
            if out and out[-1] in HANDAKUTEN:
                out[-1] = HANDAKUTEN[out[-1]]
            continue
        out.append(ch)
    return "".join(
        chr(ord(ch) - 0x60) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in out
    )


def is_kana(ch: str) -> bool:
    code = ord(ch)
    return 0x3041 <= code <= 0x30FA or 0x30FC <= code <= 0x30FE


# ---------------------------------------------------------------------------------------------
# IPADIC loading
# ---------------------------------------------------------------------------------------------

CSV_MIN_COLUMNS = 13
SURFACE_COLUMN = 0
COST_COLUMN = 3


def download_ipadic(destination: str) -> str:
    if os.path.isfile(destination):
        return destination
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    print("downloading " + IPADIC_URL)
    with urllib.request.urlopen(IPADIC_URL, timeout=180) as response:
        payload = response.read()
    with open(destination, "wb") as handle:
        handle.write(payload)
    return destination


def _read_text(path: str) -> str:
    with open(path, "rb") as handle:
        raw = handle.read()
    for encoding in ("utf-8", "euc_jp"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("euc_jp", errors="replace")


def extract_csv_folder(ipadic: str, workdir: str) -> tuple[str, str]:
    """Returns (folder with the CSVs, COPYING text)."""
    if os.path.isdir(ipadic):
        return ipadic, _read_text(os.path.join(ipadic, "COPYING"))
    with tarfile.open(ipadic, "r:*") as archive:
        archive.extractall(workdir)
        copying_member = next(
            (name for name in archive.getnames() if name.endswith("/COPYING")), None
        )
        copying = _read_text(os.path.join(workdir, copying_member)) if copying_member else ""
    folders = sorted(
        path for path in glob.glob(os.path.join(workdir, "mecab-ipadic-*")) if os.path.isdir(path)
    )
    if not folders:
        raise SystemExit("IPADIC archive did not contain a mecab-ipadic-* folder")
    return folders[0], copying


def read_keys(folder: str) -> tuple[dict[str, int], int]:
    """All-kana IPADIC surfaces, folded, with the minimum cost per key."""
    keys: dict[str, int] = {}
    rows = 0
    for path in sorted(glob.glob(os.path.join(folder, "*.csv"))):
        with open(path, encoding="euc_jp", errors="strict") as handle:
            for line in handle:
                columns = line.rstrip("\n").split(",")
                if len(columns) < CSV_MIN_COLUMNS:
                    continue
                rows += 1
                surface = columns[SURFACE_COLUMN]
                if not surface or not all(is_kana(ch) for ch in surface):
                    continue
                key = fold_kana(surface)
                if not key or not all(is_kana(ch) for ch in key):
                    continue
                cost = int(columns[COST_COLUMN])
                previous = keys.get(key)
                if previous is None or cost < previous:
                    keys[key] = cost
    return keys, rows


# ---------------------------------------------------------------------------------------------
# Minimised DAWG
# ---------------------------------------------------------------------------------------------

class Node:
    __slots__ = ("children", "final", "state")

    def __init__(self) -> None:
        self.children: dict[str, "Node"] = {}
        self.final = False
        self.state = -1


def build_dawg(keys: list[str]) -> tuple[list[tuple[bool, dict[str, int]]], int, int]:
    """Minimised DAWG: (states as (is_final, {label_char: target_id}), edge count, trie nodes)."""
    root = Node()
    for key in sorted(keys):
        node = root
        for ch in key:
            node = node.children.setdefault(ch, Node())
        node.final = True

    registry: dict[tuple, int] = {}
    states: list[tuple[bool, dict[str, int]]] = []
    trie_nodes = 0

    sys.setrecursionlimit(max(10000, len(keys) * 2))

    def intern(node: Node) -> int:
        nonlocal trie_nodes
        trie_nodes += 1
        children = tuple(sorted((ch, intern(child)) for ch, child in node.children.items()))
        signature = (node.final, children)
        existing = registry.get(signature)
        if existing is not None:
            node.state = existing
            return existing
        state_id = len(states)
        states.append((node.final, {ch: child for ch, child in children}))
        registry[signature] = state_id
        node.state = state_id
        return state_id

    root_state = intern(root)
    # The minimiser creates states bottom-up, so the root is the last id; the reader needs it at 0.
    order = [root_state] + [state_id for state_id in range(len(states)) if state_id != root_state]
    remap = {old: new for new, old in enumerate(order)}
    reordered = [
        (final, {ch: remap[target] for ch, target in children.items()})
        for final, children in (states[old] for old in order)
    ]
    edge_count = sum(len(children) for _final, children in reordered)
    return reordered, edge_count, trie_nodes


# ---------------------------------------------------------------------------------------------
# Binary writer / reader
# ---------------------------------------------------------------------------------------------

def encode(states: list[tuple[bool, dict[str, int]]], meta: dict, alphabet: str) -> bytes:
    edge_count = sum(len(children) for _final, children in states)
    meta_lines = [
        "source=" + str(meta["source"]),
        "source_sha256=" + str(meta["source_sha256"]),
        "built=" + str(meta["built"]),
        "entries=" + str(meta["entries"]),
        "fold=1",
        "generator=" + GENERATOR_VERSION,
        "alphabet=" + alphabet,
    ]
    meta_bytes = ("\n".join(meta_lines) + "\n").encode("utf-8")
    label_of = {ch: index for index, ch in enumerate(alphabet)}

    out = io.BytesIO()
    out.write(MAGIC)
    out.write(struct.pack(">HH", FORMAT_VERSION, len(meta_bytes)))
    out.write(meta_bytes)
    out.write(struct.pack(">II", len(states), edge_count))

    edge_start = 0
    for final, state in states:
        children = sorted(state.items(), key=lambda item: label_of[item[0]])
        out.write(struct.pack(">BBHIh", 1 if final else 0, 0, len(children), edge_start, 0))
        edge_start += len(children)

    for _final, state in states:
        for ch, target in sorted(state.items(), key=lambda item: label_of[item[0]]):
            out.write(struct.pack(">BI", label_of[ch], target))

    return out.getvalue()


def decode_meta(payload: bytes) -> tuple[dict, str, int]:
    """Reader used by --check; mirrors KanaDictionary.fromBytes."""
    if payload[:4] != MAGIC:
        raise SystemExit("bad magic in existing asset")
    version, meta_length = struct.unpack(">HH", payload[4:8])
    if version != FORMAT_VERSION:
        raise SystemExit("unexpected format version " + str(version))
    meta: dict[str, str] = {}
    for line in payload[8 : 8 + meta_length].decode("utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            meta[key] = value
    state_count, _edge_count = struct.unpack(">II", payload[8 + meta_length : 16 + meta_length])
    return meta, meta.get("alphabet", ""), state_count


def spectrum(payload: bytes) -> bytes:
    """Canonical form for reproducibility: metadata without built= plus a body digest."""
    meta, _, _ = decode_meta(payload)
    _version, meta_length = struct.unpack(">HH", payload[4:8])
    body = payload[8 + meta_length :]
    header = "\n".join(
        key + "=" + value for key, value in sorted(meta.items()) if key != "built"
    )
    return header.encode("utf-8") + b"\n--\n" + hashlib.sha256(body).digest()


# ---------------------------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------------------------

def build(ipadic: str, assets: str, timestamp: str) -> int:
    workdir = tempfile.mkdtemp(prefix="ipadic-")
    folder, copying = extract_csv_folder(ipadic, workdir)
    keys, rows = read_keys(folder)
    if not keys:
        raise SystemExit("no kana surfaces found; is this IPADIC?")

    states, edge_count, trie_nodes = build_dawg(list(keys))
    alphabet = "".join(sorted({ch for _final, children in states for ch in children}))
    if len(alphabet) > 255:
        raise SystemExit("alphabet too large for one-byte labels: " + str(len(alphabet)))

    with open(ipadic, "rb") as handle:
        source_hash = hashlib.sha256(handle.read()).hexdigest()

    payload = encode(
        states,
        {
            "source": SOURCE_LABEL,
            "source_sha256": source_hash,
            "built": timestamp,
            "entries": str(len(keys)),
        },
        alphabet,
    )

    os.makedirs(assets, exist_ok=True)
    os.makedirs(os.path.join(assets, "licenses"), exist_ok=True)
    asset_path = os.path.join(assets, ASSET_NAME)
    with open(asset_path, "wb") as handle:
        handle.write(payload)
    if copying:
        with open(os.path.join(assets, "licenses", LICENSE_NAME), "w", encoding="utf-8") as handle:
            handle.write(copying)

    print(
        "entries=" + str(len(keys))
        + " ipadic_rows=" + str(rows)
        + " states=" + str(len(states))
        + " edges=" + str(edge_count)
        + " trie_nodes=" + str(trie_nodes)
        + " alphabet=" + str(len(alphabet))
        + " bytes=" + str(len(payload))
    )
    print("wrote " + asset_path)
    return 0


def check(ipadic: str, assets: str) -> int:
    asset_path = os.path.join(assets, ASSET_NAME)
    if not os.path.isfile(asset_path):
        raise SystemExit("missing asset: " + asset_path)
    with open(asset_path, "rb") as handle:
        existing = handle.read()
    meta, _, _ = decode_meta(existing)
    workdir = tempfile.mkdtemp(prefix="ipadic-")
    folder, _ = extract_csv_folder(ipadic, workdir)
    keys, _ = read_keys(folder)
    states, _, _ = build_dawg(list(keys))
    alphabet = "".join(sorted({ch for _final, children in states for ch in children}))
    rebuilt = encode(
        states,
        {
            "source": meta.get("source", SOURCE_LABEL),
            "source_sha256": meta.get("source_sha256", ""),
            "built": meta.get("built", ""),
            "entries": str(len(keys)),
        },
        alphabet,
    )
    if spectrum(rebuilt) != spectrum(existing):
        raise SystemExit("asset is stale: rebuild with scripts/build_kana_dictionary.py")
    print("OK: asset matches a fresh build (" + str(len(existing)) + " bytes, "
          + str(len(keys)) + " entries)")
    return 0


def self_test() -> int:
    probes = {
        "\u30de\u30b8\u30ab\u30eb": "\u307e\u3058\u304b\u308b",
        "\u30b7\u30ab\u30b1": "\u3057\u304b\u3051",
        "\uff8a\uff9e\uff9d": "\u3070\u3093",
        "\u30b3\u30fc\u30c9": "\u3053\u30fc\u3069",
        "\u4e00\u4e8c": "\u4e00\u4e8c",
    }
    failures = 0
    for source, expected in probes.items():
        actual = fold_kana(source)
        status = "ok" if actual == expected else "FAIL"
        failures += 0 if actual == expected else 1
        print(status + " fold " + repr(source) + " -> " + repr(actual)
              + " (expected " + repr(expected) + ")")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Build the kana dictionary asset.")
    parser.add_argument("--ipadic", help="IPADIC 2.7.0 tarball or extracted folder")
    parser.add_argument("--assets", default=DEFAULT_ASSETS)
    parser.add_argument("--timestamp", help="override the built= header value (UTC ISO-8601)")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--allow-unpinned", action="store_true")
    arguments = parser.parse_args(argv)

    if arguments.self_test:
        return self_test()

    ipadic = arguments.ipadic
    if ipadic is None:
        ipadic = download_ipadic(os.path.join(tempfile.gettempdir(), "ipadic-2.7.0-20070801.tar.gz"))

    if os.path.isfile(ipadic) and not arguments.allow_unpinned:
        with open(ipadic, "rb") as handle:
            actual = hashlib.sha256(handle.read()).hexdigest()
        if actual != IPADIC_SHA256:
            raise SystemExit(
                "IPADIC SHA-256 mismatch: " + actual + " != " + IPADIC_SHA256
            )

    if arguments.check:
        return check(ipadic, arguments.assets)

    timestamp = arguments.timestamp or datetime.datetime.now(
        datetime.timezone.utc
    ).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    return build(ipadic, arguments.assets, timestamp)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
