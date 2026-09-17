# .so の ELF 構造を概観する。アーキテクチャ・セクション・依存ライブラリを確認する。
# 移植元: ../VoiceCord/re/triage.py (pefile/PE 版)
import argparse
import os
import sys

from elftools.elf.dynamic import DynamicSection
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection


def iter_so(path):
    if os.path.isfile(path):
        yield path
        return
    for root, _dirs, files in os.walk(path):
        for name in sorted(files):
            if name.endswith(".so"):
                yield os.path.join(root, name)


def triage(path, n_exports):
    with open(path, "rb") as fp:
        elf = ELFFile(fp)
        print("\n########## %s ##########" % path)
        print("  == Arch ==      %s (%s)" % (elf.get_machine_arch(), elf.elfclass))
        print("  == Type ==      %s" % elf.header["e_type"])
        print("  == Entry ==     %s" % hex(elf.header["e_entry"]))
        print("  == Size ==      %.2f MB" % (os.path.getsize(path) / 1024 / 1024))

        print("  == Sections ==")
        for sec in elf.iter_sections():
            if not sec.name:
                continue
            print("    %-20s addr=%-12s size=%-10d type=%s"
                  % (sec.name, hex(sec["sh_addr"]), sec["sh_size"], sec["sh_type"]))

        print("  == DT_NEEDED (依存 .so) ==")
        needed = []
        for sec in elf.iter_sections():
            if isinstance(sec, DynamicSection):
                for tag in sec.iter_tags():
                    if tag.entry.d_tag == "DT_NEEDED":
                        needed.append(tag.needed)
        print("    " + (", ".join(needed) if needed else "(none)"))

        print("  == Symbols (先頭 %d) ==" % n_exports)
        shown = 0
        for sec in elf.iter_sections():
            if not isinstance(sec, SymbolTableSection):
                continue
            print("    --- %s (%d symbols) ---" % (sec.name, sec.num_symbols()))
            for sym in sec.iter_symbols():
                if not sym.name or shown >= n_exports:
                    continue
                print("      %-12s %s" % (hex(sym["st_value"]), sym.name))
                shown += 1


def main():
    ap = argparse.ArgumentParser(description=".so の ELF 構造を概観する")
    ap.add_argument("path", help="lib/arm64-v8a/ ディレクトリ、または .so ファイル")
    ap.add_argument("--exports", type=int, default=40,
                    help="表示するシンボル数 (既定: 40)")
    args = ap.parse_args()
    for so in iter_so(args.path):
        try:
            triage(so, args.exports)
        except Exception as exc:
            print("  [skip] %s: %s" % (so, exc))
    return 0


if __name__ == "__main__":
    sys.exit(main())
