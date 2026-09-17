# libkrisp_wrapper.so の NC clean 関数を「プロセス自身の dlsym」で解決してフックする。
#
# この端末(Android/XOM/APK埋込lib)では frida の module パーサが破綻し、
# findExportByName/getGlobalExportByName/enumerateExports が全滅する。
# そこで dlopen(soname, RTLD_NOLOAD)+dlsym でリンカの正規リゾルバから実アドレスを得る。
#
# 使い方:
#   1. Discord をアイドル(VC未参加)にする
#   2. python re/frida_krisp_dlsym.py --host 127.0.0.1:27042 [--inject] [--seconds 60]
#   3. スクリプト起動後に実機で VC に参加する(音声中の attach はクラッシュするため順序厳守)
#
# --inject: post-Krisp 出力(x3)にサイン波を加算(別端末で可聴か確認する)。
import argparse
import subprocess
import sys
import time

import frida

# 実際に呼ばれる関数を特定するため int16/float・素/WithStats を全部フックして観測する。
CANDS = [
    "krispAudioNcCleanAmbientNoiseInt16",
    "krispAudioNcWithStatsCleanAmbientNoiseInt16",
    "krispAudioNcCleanAmbientNoiseFloat",
    "krispAudioNcWithStatsCleanAmbientNoiseFloat",
]

JS = r"""
var CANDS = %s, INJECT = %s, AMP = %f, FREQ = %f, SR = 48000.0;
var done = false, phase = 0.0, TWO_PI = Math.PI * 2;
var dlopen = new NativeFunction(Module.getGlobalExportByName("dlopen"), 'pointer', ['pointer','int']);
var dlsym  = new NativeFunction(Module.getGlobalExportByName("dlsym"),  'pointer', ['pointer','pointer']);

function hook(name, addr) {
  var isFloat = /Float/.test(name);
  var st = {n:0, last:0, maxgap:0, frames:{}};
  Interceptor.attach(addr, {
    onEnter: function (args) {
      var now = Date.now();
      if (st.last > 0) { var g = now - st.last; if (g > st.maxgap) st.maxgap = g; }
      st.last = now; st.n++;
      var fs = args[2].toInt32();
      if (fs > 0 && fs <= 8192) st.frames[fs] = (st.frames[fs]||0) + 1;
      this.out = args[3]; this.fs = fs; this.isFloat = isFloat;
      if (st.n <= 3) {
        function rms(p, c) { try { var s=0; for (var k=0;k<c;k++){ var v=isFloat?p.add(k*4).readFloat():p.add(k*2).readS16()/32768.0; s+=v*v; } return Math.sqrt(s/c).toFixed(5); } catch(e){ return "?"; } }
        var c = (fs>0&&fs<=4096)?fs:480;
        send("[call] "+name+" cnt="+fs+" a1(in)rms="+rms(args[1],c)+" a3(out)rms="+rms(args[3],c)+" type="+(isFloat?"float":"int16"));
      }
    },
    onLeave: function () {
      if (!INJECT) return;
      var out = this.out, fs = this.fs; if (fs<=0||fs>8192) return;
      for (var i=0;i<fs;i++){ var s = AMP*Math.sin(phase); phase += TWO_PI*FREQ/SR; if(phase>TWO_PI)phase-=TWO_PI;
        if (this.isFloat){ out.add(i*4).writeFloat(out.add(i*4).readFloat()+s); }
        else { var v=out.add(i*2).readS16()+Math.round(s*32767); if(v>32767)v=32767; else if(v<-32768)v=-32768; out.add(i*2).writeS16(v); } }
    }
  });
  send("[HOOK] "+name+" @ "+addr+(INJECT?" (injecting)":""));
  setInterval(function(){ var o=[]; for(var k in st.frames)o.push(k+"x"+st.frames[k]); if(st.n>0)send("["+name+"] calls/s="+st.n+" maxgap="+st.maxgap+"ms frame{"+o.join(" ")+"}"); st.n=0;st.maxgap=0;st.frames={}; },1000);
}

function go() {
  if (done) return;
  if (!Process.enumerateModules().filter(function(m){return /krisp/i.test(m.name);}).length) return;
  done = true;
  var h = dlopen(Memory.allocUtf8String("libkrisp_wrapper.so"), 6); // RTLD_NOW|RTLD_NOLOAD
  send("[dlopen] handle="+h);
  if (h.isNull()) { send("dlopen NOLOAD 失敗"); return; }
  var hooks = 0;
  CANDS.forEach(function(name){
    var a = dlsym(h, Memory.allocUtf8String(name));
    send(name+" -> "+a);
    if (!a.isNull()) { try { hook(name, a); hooks++; } catch(e){ send("[attach ERR] "+name+": "+e); } }
  });
  send("[hooks="+hooks+"]");
}

Process.attachModuleObserver({ onAdded: function(m){ if(/krisp/i.test(m.name)){ send("[krisp loaded]"); setTimeout(go, 2500); } } });
if (Process.enumerateModules().filter(function(m){return /krisp/i.test(m.name);}).length) setTimeout(go, 500);
send("[ready] スクリプト起動。いま実機で VC に参加してください");
"""


def main():
    ap = argparse.ArgumentParser(description="dlsym経由でKrisp NC関数をフック(XOM/APK埋込対応)")
    ap.add_argument("--host", default="127.0.0.1:27042", help="gadget 接続先")
    ap.add_argument("--inject", action="store_true", help="post-Krisp出力にサイン波を加算")
    ap.add_argument("--amp", type=float, default=0.25)
    ap.add_argument("--freq", type=float, default=440.0)
    ap.add_argument("--seconds", type=int, default=60, help="観測秒数(この間にVC参加)")
    args = ap.parse_args()

    js = JS % (str(CANDS), "true" if args.inject else "false", args.amp, args.freq)
    dev = frida.get_device_manager().add_remote_device(args.host)
    s = dev.attach("Gadget")
    sc = s.create_script(js)
    sc.on("message", lambda m, d: (print(m.get("payload") if m.get("type") == "send" else m), sys.stdout.flush()))
    sc.load()
    print(">>> いま端末で VC に参加してください <<<")
    time.sleep(args.seconds)


if __name__ == "__main__":
    main()
