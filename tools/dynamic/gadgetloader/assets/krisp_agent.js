// gadget script モードでプロセス内自動実行される agent。
// 外部 attach を使わないため、音声中でもクラッシュしない(スレッド一時停止が起きない)。
// dlopen(RTLD_NOLOAD)+dlsym で libkrisp_wrapper.so の NC clean 関数を解決し、
// post-Krisp 出力(x3)にサイン波を加算する。出力は console.log → logcat(tag Frida)。
(function () {
  var CANDS = [
    "krispAudioNcCleanAmbientNoiseInt16",
    "krispAudioNcWithStatsCleanAmbientNoiseInt16",
    "krispAudioNcCleanAmbientNoiseFloat",
    "krispAudioNcWithStatsCleanAmbientNoiseFloat",
  ];
  var INJECT = true, AMP = 0.25, FREQ = 440.0, SR = 48000.0;
  var TWO_PI = Math.PI * 2, phase = 0.0, done = false;

  var LOGPATH = "/data/data/com.discord/cache/vc_agent.log";
  function log(s) {
    var line = "[VC] " + s;
    console.log(line);
    try { var f = new File(LOGPATH, "a"); f.write(line + String.fromCharCode(10)); f.flush(); f.close(); } catch (e) {}
  }

  var dlopen = new NativeFunction(Module.getGlobalExportByName("dlopen"), 'pointer', ['pointer', 'int']);
  var dlsym  = new NativeFunction(Module.getGlobalExportByName("dlsym"),  'pointer', ['pointer', 'pointer']);

  function hook(name, addr) {
    var isFloat = /Float/.test(name);
    var st = { n: 0, last: 0, maxgap: 0, frames: {} };
    Interceptor.attach(addr, {
      onEnter: function (args) {
        var now = Date.now();
        if (st.last > 0) { var g = now - st.last; if (g > st.maxgap) st.maxgap = g; }
        st.last = now; st.n++;
        var fs = args[2].toInt32();
        if (fs > 0 && fs <= 8192) st.frames[fs] = (st.frames[fs] || 0) + 1;
        this.out = args[3]; this.fs = fs; this.isFloat = isFloat;
        if (st.n <= 3) {
          function rms(p, c) { try { var s = 0; for (var k = 0; k < c; k++) { var v = isFloat ? p.add(k * 4).readFloat() : p.add(k * 2).readS16() / 32768.0; s += v * v; } return Math.sqrt(s / c).toFixed(5); } catch (e) { return "?"; } }
          var c = (fs > 0 && fs <= 4096) ? fs : 480;
          log("call " + name + " cnt=" + fs + " in.rms=" + rms(args[1], c) + " out.rms=" + rms(args[3], c) + " type=" + (isFloat ? "float" : "int16"));
        }
      },
      onLeave: function () {
        if (!INJECT) return;
        var out = this.out, fs = this.fs; if (fs <= 0 || fs > 8192) return;
        for (var i = 0; i < fs; i++) {
          var s = AMP * Math.sin(phase); phase += TWO_PI * FREQ / SR; if (phase > TWO_PI) phase -= TWO_PI;
          if (this.isFloat) { out.add(i * 4).writeFloat(out.add(i * 4).readFloat() + s); }
          else { var v = out.add(i * 2).readS16() + Math.round(s * 32767); if (v > 32767) v = 32767; else if (v < -32768) v = -32768; out.add(i * 2).writeS16(v); }
        }
      }
    });
    log("HOOK " + name + " @ " + addr + (INJECT ? " (injecting " + FREQ + "Hz)" : ""));
    setInterval(function () { var o = []; for (var k in st.frames) o.push(k + "x" + st.frames[k]); if (st.n > 0) log(name + " calls/s=" + st.n + " maxgap=" + st.maxgap + "ms frame{" + o.join(" ") + "}"); st.n = 0; st.maxgap = 0; st.frames = {}; }, 1000);
  }

  var polling = false;
  function startPolling(reason) {
    if (polling || done) return; polling = true;
    log("polling開始(" + reason + ")");
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      var h = dlopen(Memory.allocUtf8String("libkrisp_wrapper.so"), 6); // RTLD_NOW|RTLD_NOLOAD
      if (h.isNull()) { if (tries >= 60) { clearInterval(iv); polling = false; log("polling終了(krisp未ロード, tries=" + tries + ")"); } return; }
      clearInterval(iv); done = true;
      log("dlopen handle=" + h + " (tries=" + tries + ")");
      var hooks = 0;
      CANDS.forEach(function (name) {
        var a = dlsym(h, Memory.allocUtf8String(name));
        log(name + " -> " + a);
        if (!a.isNull()) { try { hook(name, a); hooks++; } catch (e) { log("attach ERR " + name + ": " + e); } }
      });
      log("hooks=" + hooks);
    }, 500);
  }

  try {
    Process.attachModuleObserver({ onAdded: function (m) { if (/krisp/i.test(m.name)) { log("krisp loaded"); startPolling("observer"); } } });
  } catch (e) { log("observer ERR " + e); }
  // 保険: 常時ポーリングも回す(observer が取りこぼしても拾う)
  startPolling("initial");
  log("agent ready (script mode)");
})();
