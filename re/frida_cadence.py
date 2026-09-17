# フック候補の呼び出し頻度を計測する。
# 移植元: ../VoiceCord/re/frida_cadence.py
#
# ここで確かめること(設計書の未決事項):
#   - 20ms フレームなら 50 calls/s、10ms なら 100 calls/s
#   - ミュート中 / VAD 無音時に呼び出しが止まるか(止まるなら再生中は VAD も要フック)
# ミュートを押した瞬間に calls/s が 0 に落ちるかを目視すること。
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common  # noqa: E402

JS = r"""
const t = resolveTarget();
if (t === null) {
  send({line: "[!] フック候補を解決できない。frida_find.py で候補を確認すること。"});
} else {
  send({line: "hook " + describe(t)});
  let n = 0;
  let last = 0;      // 前回呼び出し時刻(ms)
  let maxgap = 0;    // 最大呼び出し間隔(ms)
  let gaps = [];     // 閾値超のギャップ
  let frames = {};   // frame_size の分布
  Interceptor.attach(t.addr, {
    onEnter(args) {
      const now = Date.now();
      if (last > 0) {
        const g = now - last;
        if (g > maxgap) maxgap = g;
        if (g > GAP_MS) gaps.push(g);
      }
      last = now;
      n++;
      // opus_encode(st, pcm, frame_size, ...) / KrispNCProcess(?, in, cnt, out)
      // どちらも第3引数(args[2])がサンプル数。
      const fs = args[2].toInt32();
      if (fs > 0 && fs <= 8192) frames[fs] = (frames[fs] || 0) + 1;
    }
  });
  setInterval(function () {
    const dist = Object.keys(frames).map(function (k) {
      return k + "x" + frames[k];
    }).join(" ");
    send({line: "calls/s=" + n + "  maxgap=" + maxgap + "ms  gaps>" + GAP_MS +
                "ms=" + gaps.length + " [" + gaps.slice(0, 10).join(",") + "]" +
                "  frame_size{" + dist + "}"});
    n = 0; maxgap = 0; gaps = []; frames = {};
  }, 1000);
  send({line: "[cadence probe installed] ミュート/発声を切り替えて calls/s の変化を見ること"});
}
"""


def main():
    ap = _common.parser("フック候補の呼び出し頻度とフレーム長分布を計測する")
    _common.add_target_args(ap)
    ap.add_argument("--gap-ms", type=int, default=30,
                    help="この ms 超の呼び出し間隔を欠落として記録する (既定: 30)")
    args = ap.parse_args()
    js = (_common.load_resolve_js(args.patterns, args.discord_version)
          + _common.target_js(args)
          + ("const GAP_MS = %d;\n" % args.gap_ms)
          + JS)
    _common.run(args, js)


if __name__ == "__main__":
    main()
