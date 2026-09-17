# フック候補のバッファにサイン波を加算注入する。フェーズ0の可否判定の核。
# 移植元: ../VoiceCord/re/frida_inject.py (KrispNCProcessFloat の a3 固定だったものを汎用化)
#
# 送信ストリームに混ぜるので自分には聞こえない。別端末(サブアカウント)で
# VC に入り、そちらで聞こえれば注入点として成立。
#
# 注入方式は対象で変える:
#   opus_encode 系  … 第2引数(pcm)は入力バッファ。元を壊さないよう別バッファに
#                      コピーして混ぜ、ポインタを差し替える(設計書の hooked_opus_encode と同じ考え方)。
#   Krisp 系        … 第4引数(out)は出力バッファそのもの。onLeave で直接加算する
#                      (デスクトップ版と同じ)。
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common  # noqa: E402

JS = r"""
const SR = 48000.0, TWO_PI = Math.PI * 2;
const t = resolveTarget();

if (t === null) {
  send({line: "[!] フック候補を解決できない。frida_find.py で候補を確認すること。"});
} else {
  const isFloat = isFloatTarget(t.name);
  const isOpus = /opus/i.test(t.name);
  send({line: "hook " + describe(t)});
  send({line: "  type=" + (isFloat ? "float32" : "int16") +
              "  方式=" + (isOpus ? "入力差し替え(a1)" : "出力加算(a3)") +
              "  AMP=" + AMP + " FREQ=" + FREQ + " CH=" + CHANNELS});

  let phase = 0.0;
  let n = 0;
  let scratch = null, scratchBytes = 0;

  function nextSample() {
    const s = AMP * Math.sin(phase);
    phase += TWO_PI * FREQ / SR;
    if (phase > TWO_PI) phase -= TWO_PI;
    return s;
  }

  // バッファ p の先頭 total サンプルにサイン波を加算する。
  // ステレオのときは同じ値を両チャンネルに乗せる(設計書のミキサ仕様に合わせる)。
  function mixInto(p, frames, ch) {
    for (let i = 0; i < frames; i++) {
      const s = nextSample();
      for (let c = 0; c < ch; c++) {
        const idx = i * ch + c;
        if (isFloat) {
          const cur = p.add(idx * 4).readFloat();
          p.add(idx * 4).writeFloat(cur + s);
        } else {
          let v = p.add(idx * 2).readS16() + Math.round(s * 32767);
          if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
          p.add(idx * 2).writeS16(v);
        }
      }
    }
  }

  Interceptor.attach(t.addr, {
    onEnter(args) {
      this.frames = args[2].toInt32();
      this.out = args[3];
      if (this.frames <= 0 || this.frames > 4096) { this.skip = true; return; }
      this.skip = false;

      if (isOpus) {
        // 入力 pcm は const 扱い。コピーに混ぜてポインタを差し替える。
        const bytes = this.frames * CHANNELS * (isFloat ? 4 : 2);
        if (scratch === null || scratchBytes < bytes) {
          scratch = Memory.alloc(bytes);
          scratchBytes = bytes;
        }
        Memory.copy(scratch, args[1], bytes);
        mixInto(scratch, this.frames, CHANNELS);
        args[1] = scratch;
        n++;
      }
    },
    onLeave(ret) {
      if (this.skip || isOpus) return;
      // Krisp 系: a3 が post-Krisp の出力バッファ。ここに直接足す。
      mixInto(this.out, this.frames, CHANNELS);
      n++;
    }
  });

  send({line: "[sine injection active] 別端末で VC に入り、聞こえるか確認すること"});
  let ticks = 0;
  const iv = setInterval(function () {
    ticks++;
    send({line: "[t" + ticks + "] injected frames=" + n});
    n = 0;
    if (ticks >= DURATION) { clearInterval(iv); send({line: "[注入終了]"}); }
  }, 1000);
}
"""


def main():
    ap = _common.parser("フック候補にサイン波を注入し、別端末で可聴か確かめる")
    _common.add_target_args(ap)
    ap.add_argument("--amp", type=float, default=0.25, help="振幅 0-1 (既定: 0.25)")
    ap.add_argument("--freq", type=float, default=440.0, help="周波数 Hz (既定: 440)")
    ap.add_argument("--channels", type=int, default=1,
                    help="バッファのチャンネル数。ステレオなら 2 (既定: 1)")
    ap.add_argument("--duration", type=int, default=120,
                    help="注入を続ける秒数 (既定: 120)")
    args = ap.parse_args()
    js = (_common.load_resolve_js(args.patterns, args.discord_version)
          + _common.target_js(args)
          + ("const AMP = %f;\nconst FREQ = %f;\nconst CHANNELS = %d;\nconst DURATION = %d;\n"
             % (args.amp, args.freq, args.channels, args.duration))
          + JS)
    _common.run(args, js)


if __name__ == "__main__":
    main()
