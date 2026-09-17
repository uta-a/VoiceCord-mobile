# フック候補の引数とバッファ内容を観察し、署名(どの引数が PCM か・型・サンプル数)を特定する。
# 移植元: ../VoiceCord/re/frida_krisp.py (KrispNCProcessFloat 固定だったものを汎用化)
#
# ここで確かめること:
#   - どの引数が PCM バッファか(rms が音声らしく動くのはどれか)
#   - int16 か float32 か
#   - サンプル数(第3引数)が 480(=48kHz/10ms) か 960(=20ms) か
#   - opus_encode の場合、送信用エンコーダのインスタンス(第1引数)が何種類現れるか
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common  # noqa: E402

JS = r"""
const t = resolveTarget();
if (t === null) {
  send({line: "[!] フック候補を解決できない。frida_find.py で候補を確認すること。"});
} else {
  const isFloat = isFloatTarget(t.name);
  send({line: "hook " + describe(t) + "  type=" + (isFloat ? "float32" : "int16")});

  function peek(p, n) {
    try {
      const a = [];
      for (let i = 0; i < n; i++) {
        a.push(isFloat ? p.add(i * 4).readFloat().toFixed(4)
                       : p.add(i * 2).readS16());
      }
      return a.join(",");
    } catch (e) { return "<unreadable>"; }
  }
  function rms(p, n) {
    try {
      let s = 0;
      for (let i = 0; i < n; i++) {
        const v = isFloat ? p.add(i * 4).readFloat()
                          : p.add(i * 2).readS16() / 32768.0;
        s += v * v;
      }
      return Math.sqrt(s / n).toFixed(5);
    } catch (e) { return "?"; }
  }

  let n = 0;
  const instances = {};
  Interceptor.attach(t.addr, {
    onEnter(args) {
      this.args = [args[0], args[1], args[2], args[3], args[4]];
      this.cnt = args[2].toInt32();
      const inst = args[0].toString();
      instances[inst] = (instances[inst] || 0) + 1;
      if (n < SHOW) {
        send({line: "\n[call#" + n + "] a0=" + args[0] + " a1=" + args[1] +
                    " a2(cnt)=" + this.cnt + " a3=" + args[3] + " a4=" + args[4]});
        const cnt = (this.cnt > 0 && this.cnt <= 4096) ? this.cnt : 480;
        // PCM がどの引数に載っているか、rms で当たりを取る。
        for (const idx of [1, 3]) {
          send({line: "  BEFORE a" + idx + ": " + peek(this.args[idx], 6) +
                      "  rms=" + rms(this.args[idx], cnt)});
        }
      }
    },
    onLeave(ret) {
      if (n < SHOW) {
        const cnt = (this.cnt > 0 && this.cnt <= 4096) ? this.cnt : 480;
        for (const idx of [1, 3]) {
          send({line: "  AFTER  a" + idx + ": " + peek(this.args[idx], 6) +
                      "  rms=" + rms(this.args[idx], cnt)});
        }
        send({line: "  ret=" + ret});
      }
      n++;
    }
  });

  send({line: "[probe installed]"});
  let ticks = 0;
  const iv = setInterval(function () {
    ticks++;
    const keys = Object.keys(instances);
    send({line: "[t" + ticks + "] calls=" + n + "  インスタンス数=" + keys.length +
                " [" + keys.slice(0, 4).join(" ") + "]"});
    if (ticks >= 30) clearInterval(iv);
  }, 1000);
}
"""


def main():
    ap = _common.parser("フック候補の引数レイアウトと PCM バッファを観察する")
    _common.add_target_args(ap)
    ap.add_argument("--show", type=int, default=4,
                    help="詳細表示する呼び出し回数 (既定: 4)")
    args = ap.parse_args()
    js = (_common.load_resolve_js(args.patterns, args.discord_version)
          + _common.target_js(args)
          + ("const SHOW = %d;\n" % args.show)
          + JS)
    _common.run(args, js)


if __name__ == "__main__":
    main()
