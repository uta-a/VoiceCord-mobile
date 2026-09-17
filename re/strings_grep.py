# .so の ASCII 文字列を走査し、キーワードでヒットを表示する。
# 移植元: ../VoiceCord/re/strings_grep.py (単一 .node 固定パス版)
# シンボルが完全 strip されていても、文字列から対象 .so を絞り込める。
import argparse
import os
import re
import sys

KW = [
    b"opus", b"Opus", b"OPUS", b"libopus",
    b"krisp", b"Krisp", b"KRISP",
    b"webrtc", b"WebRtc", b"WebRTC",
    b"audio_processing", b"AudioProcessing", b"ProcessStream",
    b"AudioEncoder", b"AudioSendStream", b"AudioMixer", b"Mixer",
    b"celt", b"silk", b"encode", b"Encode",
]


def iter_so(path):
    if os.path.isfile(path):
        yield path
        return
    for root, _dirs, files in os.walk(path):
        for name in sorted(files):
            if name.endswith(".so"):
                yield os.path.join(root, name)


def scan(path, per_kw, min_len):
    data = open(path, "rb").read()
    seen = {}
    for m in re.finditer(rb"[\x20-\x7e]{%d,}" % min_len, data):
        s = m.group()
        for kw in KW:
            if kw in s:
                k = kw.decode()
                seen.setdefault(k, [])
                if len(seen[k]) < per_kw:
                    seen[k].append((hex(m.start()), s.decode(errors="replace")[:70]))
                break
    return seen


def main():
    ap = argparse.ArgumentParser(
        description=".so の文字列からボイス関連ライブラリを絞り込む")
    ap.add_argument("path", help="lib/arm64-v8a/ ディレクトリ、または .so ファイル")
    ap.add_argument("--per-kw", type=int, default=6,
                    help="キーワードごとの表示件数 (既定: 6)")
    ap.add_argument("--min-len", type=int, default=5,
                    help="ASCII 連続とみなす最小長 (既定: 5)")
    args = ap.parse_args()

    ranked = []
    for so in iter_so(args.path):
        seen = scan(so, args.per_kw, args.min_len)
        if not seen:
            continue
        ranked.append((len(seen), so, seen))

    if not ranked:
        print("キーワードに一致する文字列が1件も無い。")
        return 1

    # ヒットしたキーワード種類数が多い .so ほど本命に近い。
    ranked.sort(reverse=True, key=lambda x: x[0])
    for n_kw, so, seen in ranked:
        print("\n########## %s  (ヒット種類=%d) ##########" % (so, n_kw))
        for kw in KW:
            k = kw.decode()
            if k not in seen:
                continue
            print("  === %s ===" % k)
            for off, s in seen[k]:
                print("    file+%s: %s" % (off, s))
    return 0


if __name__ == "__main__":
    sys.exit(main())
