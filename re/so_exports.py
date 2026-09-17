# .so のシンボル表を走査し、Opus / Krisp 系のフック候補を洗い出す。
# 移植元: ../VoiceCord/re/krisp_exports.py (pefile/PE 版)
# root 不要。フェーズ0で最初に動かすスクリプト。
import argparse
import json
import os
import sys

from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

# 設計書の候補1(Opus)と、デスクトップ版で確定済みの候補3(Krisp)を同時に走査する。
KEYWORDS = [
    "opus", "krisp", "webrtc", "audioprocessing", "processstream",
    "encode", "encoder", "capture", "voice", "mixer", "silk", "celt",
]


def iter_so(path):
    """ファイル1つ、またはディレクトリ配下の .so を列挙する。"""
    if os.path.isfile(path):
        yield path
        return
    for root, _dirs, files in os.walk(path):
        for name in sorted(files):
            if name.endswith(".so"):
                yield os.path.join(root, name)


def collect_symbols(elf):
    """.dynsym と .symtab の両方からシンボルを集める。

    戻り値: [(table_name, name, st_value, st_size)]
    strip 済みの .so は .dynsym しか持たないため、どちらから来たかを保持する。
    """
    out = []
    for sec in elf.iter_sections():
        if not isinstance(sec, SymbolTableSection):
            continue
        for sym in sec.iter_symbols():
            if not sym.name:
                continue
            out.append((sec.name, sym.name, sym["st_value"], sym["st_size"]))
    return out


def vaddr_to_offset(elf, vaddr):
    """仮想アドレス → ファイルオフセット。--emit-pattern でバイト列を読むのに使う。"""
    for sec in elf.iter_sections():
        if sec["sh_type"] == "SHT_NOBITS":
            continue
        start = sec["sh_addr"]
        if start == 0:
            continue
        if start <= vaddr < start + sec["sh_size"]:
            return vaddr - start + sec["sh_offset"]
    return None


def disasm(raw, vaddr):
    """capstone があれば逆アセンブルを併記する。無ければ静かに諦める。"""
    try:
        from capstone import CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN, Cs
    except ImportError:
        return []
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    return ["  %08x  %-8s %s" % (i.address, i.mnemonic, i.op_str)
            for i in md.disasm(raw, vaddr)]


def cmd_scan(args):
    hit_any = False
    for so in iter_so(args.path):
        with open(so, "rb") as fp:
            try:
                elf = ELFFile(fp)
            except Exception as exc:
                print("  [skip] %s: %s" % (so, exc))
                continue
            syms = collect_symbols(elf)
            tables = sorted({t for t, _n, _v, _s in syms})
            hits = [(t, n, v, s) for (t, n, v, s) in syms
                    if any(k in n.lower() for k in KEYWORDS)]

        print("\n=== %s ===" % so)
        print("  arch=%s  symbols=%d  tables=%s"
              % (elf.get_machine_arch(), len(syms), ",".join(tables) or "(none)"))
        if not syms:
            print("  シンボル無し(完全 strip)。strings_grep.py とバイトパターン走査に切り替える。")
        elif ".symtab" not in tables:
            print("  .symtab 無し(strip 済み)。動的シンボルのみで解決する必要がある。")

        if not hits:
            print("  キーワード一致なし")
            continue
        hit_any = True
        for table, name, value, size in sorted(hits, key=lambda x: x[1]):
            print("  [%s] %-8s size=%-6d %s" % (table, hex(value), size, name))

    if not hit_any:
        print("\n一致するシンボルが1件も無い。完全 strip の可能性が高いため、"
              "strings_grep.py で文字列から .so を絞り込むこと。")
    return 0


def cmd_emit_pattern(args):
    """指定シンボル先頭 N バイトを signatures/patterns.json へ貼れる形で出す。"""
    for so in iter_so(args.path):
        with open(so, "rb") as fp:
            try:
                elf = ELFFile(fp)
            except Exception:
                continue
            target = None
            for _table, name, value, size in collect_symbols(elf):
                if name == args.emit_pattern:
                    target = (name, value, size)
                    break
            if target is None:
                continue
            name, vaddr, _size = target
            off = vaddr_to_offset(elf, vaddr)
            if off is None:
                print("  [!] %s: %s の仮想アドレス %s をファイルオフセットに変換できない"
                      % (so, name, hex(vaddr)))
                continue
            fp.seek(off)
            raw = fp.read(args.length)

        # JS 側 findByPattern() が読む形 {シンボル名: {module, bytes, mask}} に合わせる。
        fragment = {
            name: {
                "module": os.path.basename(so),
                "bytes": raw.hex(),
                "mask": "ff" * len(raw),
                "vaddr": hex(vaddr),
                "note": "自動生成。分岐/リロケーションを含む箇所は mask の該当バイトを 00 にして緩めること。",
            }
        }
        print("// %s  %s @ %s (file+%s)" % (so, name, hex(vaddr), hex(off)))
        for line in disasm(raw, vaddr):
            print(line)
        print(json.dumps(fragment, indent=2, ensure_ascii=False))
        return 0

    print("シンボル %s が見つからない" % args.emit_pattern, file=sys.stderr)
    return 1


def main():
    ap = argparse.ArgumentParser(
        description="Android Discord の .so から Opus/Krisp のフック候補を洗い出す")
    ap.add_argument("path", help="lib/arm64-v8a/ ディレクトリ、または .so ファイル")
    ap.add_argument("--emit-pattern", metavar="SYMBOL",
                    help="指定シンボル先頭バイト列を patterns.json 断片として出力する")
    ap.add_argument("--length", type=int, default=16,
                    help="--emit-pattern で読むバイト数 (既定: 16)")
    args = ap.parse_args()
    return cmd_emit_pattern(args) if args.emit_pattern else cmd_scan(args)


if __name__ == "__main__":
    sys.exit(main())
