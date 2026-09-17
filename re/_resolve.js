// 対象シンボルの解決。各 frida_*.py がこの後ろに自分の JS を連結して使う。
// 解決順序: 動的シンボル → 静的シンボル → バイトパターン。
// 候補は Opus(設計書 優先1) と Krisp(実測で確定済み) を同時に見る。
//
// frida 17 で Module.* の静的メソッドが廃止され、モジュールオブジェクトのメソッドに
// 移行した(Module.findExportByName → m.findExportByName)。両対応にしてある。

const OPUS_SYMS = ["opus_encode", "opus_encode_float", "opus_encoder_create",
                   "opus_encoder_ctl", "opus_encoder_init"];
// Android の libkrisp_wrapper.so 実測エクスポート(345.9 Stable)。
// NC clean 系の第4引数(x3)が post-Krisp 出力バッファ。WithStats 版が本命候補。
const KRISP_SYMS = [
  "krispAudioNcWithStatsCleanAmbientNoiseInt16",
  "krispAudioNcWithStatsCleanAmbientNoiseFloat",
  "krispAudioNcCleanAmbientNoiseInt16",
  "krispAudioNcCleanAmbientNoiseFloat",
  "krispAudioNcWithStatsCleanAmbientNoiseWithRingtoneInt16",
  "krispAudioNcCleanAmbientNoiseWithRingtoneInt16",
  "KrispNCProcess",           // デスクトップ互換の高レベル thunk
  "KrispNCProcessFloat",
];
const MODULE_HINTS = ["voice", "discord", "krisp", "opus", "webrtc", "audio", "octave"];

function allModules() { return Process.enumerateModules(); }

function moduleByName(name) {
  try { return Process.findModuleByName(name); } catch (e) {}
  try { return Process.getModuleByName(name); } catch (e) {}
  return null;
}

function voiceModules() {
  return allModules().filter(function (m) {
    const n = m.name.toLowerCase();
    return MODULE_HINTS.some(function (h) { return n.indexOf(h) >= 0; });
  });
}

// 1モジュールからエクスポートを引く(frida17: オブジェクトメソッド)。
function exportIn(m, name) {
  try { if (typeof m.findExportByName === "function") return m.findExportByName(name); } catch (e) {}
  try { if (typeof Module.findExportByName === "function") return Module.findExportByName(m.name, name); } catch (e) {}
  return null;
}
function symbolsIn(m) {
  try { if (typeof m.enumerateSymbols === "function") return m.enumerateSymbols(); } catch (e) {}
  try { if (typeof Module.enumerateSymbols === "function") return Module.enumerateSymbols(m.name); } catch (e) {}
  return [];
}

// モジュール横断でシンボル名を探す。exports に無ければ symbols も見る。
function findSymbol(name, modules) {
  const mods = modules || allModules();
  for (const m of mods) {
    const e = exportIn(m, name);
    if (e !== null && e !== undefined && !e.isNull()) {
      return { addr: e, module: m.name, via: "export", name: name };
    }
  }
  for (const m of mods) {
    for (const s of symbolsIn(m)) {
      if (s.name === name && !s.address.isNull()) {
        return { addr: s.address, module: m.name, via: "symbol", name: name };
      }
    }
  }
  return null;
}

// signatures/patterns.json 由来のバイトパターンで走査する(シンボルが無いとき)。
function findByPattern(pattern) {
  const m = moduleByName(pattern.module);
  if (m === null) return null;
  let sig = "";
  const b = pattern.bytes, mk = pattern.mask || "ff".repeat(b.length / 2);
  for (let i = 0; i < b.length; i += 2) {
    sig += (mk.substr(i, 2) === "00") ? "?? " : (b.substr(i, 2) + " ");
  }
  const hits = Memory.scanSync(m.base, m.size, sig.trim());
  if (hits.length === 0) return null;
  return { addr: hits[0].address, module: m.name, via: "pattern", hits: hits.length };
}

function resolveAny(names, modules, patterns) {
  for (const n of names) {
    const r = findSymbol(n, modules);
    if (r !== null) { r.name = n; return r; }
  }
  if (patterns) {
    for (const n of names) {
      if (!patterns[n]) continue;
      const r = findByPattern(patterns[n]);
      if (r !== null) { r.name = n; return r; }
    }
  }
  return null;
}

function describe(r) {
  if (r === null) return "(解決できず)";
  const m = moduleByName(r.module);
  const rva = m ? (" rva 0x" + r.addr.sub(m.base).toString(16)) : "";
  return r.name + " @ " + r.addr + " in " + r.module + " (" + rva + ", via " + r.via + ")";
}

// TARGET_SPEC は各 frida_*.py が _common.target_js() で宣言する。
function resolveTarget() {
  let names;
  if (Array.isArray(TARGET_SPEC)) names = TARGET_SPEC;
  else if (TARGET_SPEC === "OPUS") names = OPUS_SYMS;
  else if (TARGET_SPEC === "KRISP") names = KRISP_SYMS;
  else names = OPUS_SYMS.concat(KRISP_SYMS);

  // create/ctl/init/reset/close は注入地点ではないので候補から外す。
  names = names.filter(function (n) { return !/create|ctl|init|reset|close/i.test(n); });

  const pri = voiceModules();
  const priNames = pri.map(function (m) { return m.name; });
  const rest = allModules().filter(function (m) { return priNames.indexOf(m.name) < 0; });
  return resolveAny(names, pri.concat(rest), PATTERNS);
}

// *Float は float32、それ以外は int16。
function isFloatTarget(name) { return /float/i.test(name); }
