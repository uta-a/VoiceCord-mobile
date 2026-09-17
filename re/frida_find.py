# ロード済み .so を列挙し、Opus / Krisp のフック候補シンボルを解決できるか確かめる。
# 移植元: ../VoiceCord/re/frida_find.py
# 動的解析の最初の1本。ここで解決できなければ以降のスクリプトは動かない。
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common  # noqa: E402

JS = r"""
send({line: "=== ボイス関連と思われるモジュール ==="});
const vm = voiceModules();
for (const m of vm) {
  send({line: "  " + m.name + "  base=" + m.base + " size=0x" + m.size.toString(16)});
}
if (vm.length === 0) {
  send({line: "  (ヒント名に一致するモジュール無し。全モジュールを出す)"});
  for (const m of Process.enumerateModules()) {
    send({line: "  " + m.name + "  base=" + m.base});
  }
}

send({line: "\n=== Opus 候補 ==="});
for (const n of OPUS_SYMS) {
  const r = findSymbol(n);
  send({line: "  " + n + ": " + (r ? describe({name:n, addr:r.addr, module:r.module, via:r.via}) : "見つからず")});
}

send({line: "\n=== Krisp 候補 ==="});
for (const n of KRISP_SYMS) {
  const r = findSymbol(n);
  send({line: "  " + n + ": " + (r ? describe({name:n, addr:r.addr, module:r.module, via:r.via}) : "見つからず")});
}

send({line: "\n=== パターン走査フォールバック ==="});
if (Object.keys(PATTERNS).length === 0) {
  send({line: "  patterns.json が空。シンボルが全滅なら so_exports.py --emit-pattern で作ること。"});
} else {
  for (const n of Object.keys(PATTERNS)) {
    const r = findByPattern(PATTERNS[n]);
    send({line: "  " + n + ": " + (r ? (r.addr + " (hits=" + r.hits + ")") : "一致なし")});
  }
}
send({line: "\n[done]"});
"""


def main():
    ap = _common.parser("ロード済み .so と Opus/Krisp 候補シンボルを列挙する")
    ap.add_argument("--patterns", default=None,
                    help="signatures/patterns.json のパス(シンボル全滅時のフォールバック)")
    args = ap.parse_args()
    if args.seconds == 0:
        args.seconds = 5  # 列挙するだけなので待ち続けない
    _common.run(args, _common.load_resolve_js(args.patterns) + JS)


if __name__ == "__main__":
    main()
