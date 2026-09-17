// 対象シンボルの解決。各 frida_*.py がこの後ろに自分の JS を連結して使う。
// 設計書の解決順序どおり: 動的シンボル → 静的シンボル → バイトパターン。
// 候補は Opus(設計書 優先1) と Krisp(デスクトップ版で確定済み) を同時に見る。

const OPUS_SYMS = ["opus_encode", "opus_encode_float", "opus_encoder_create",
                   "opus_encoder_ctl", "opus_encoder_init"];
const KRISP_SYMS = ["KrispNCProcess", "KrispNCProcessFloat", "krisp_nc_process"];
const MODULE_HINTS = ["voice", "discord", "krisp", "opus", "webrtc", "audio", "octave"];

function voiceModules() {
  return Process.enumerateModules().filter(function (m) {
    const n = m.name.toLowerCase();
    return MODULE_HINTS.some(function (h) { return n.indexOf(h) >= 0; });
  });
}

// モジュール横断でシンボル名を探す。exports に無ければ symbols も見る。
function findSymbol(name, modules) {
  const mods = modules || Process.enumerateModules();
  for (const m of mods) {
    try {
      const e = Module.findExportByName(m.name, name);
      if (e !== null) return { addr: e, module: m.name, via: "export" };
    } catch (err) { /* モジュールによっては列挙できない */ }
  }
  for (const m of mods) {
    try {
      for (const s of Module.enumerateSymbols(m.name)) {
        if (s.name === name && !s.address.isNull()) {
          return { addr: s.address, module: m.name, via: "symbol" };
        }
      }
    } catch (err) { /* strip 済みなら symbols は空 */ }
  }
  return null;
}

// signatures/patterns.json 由来のバイトパターンで走査する(シンボルが無いとき)。
// pattern = { module, bytes(hex), mask(hex) }
function findByPattern(pattern) {
  const m = Process.findModuleByName(pattern.module);
  if (m === null) return null;
  let sig = "";
  const b = pattern.bytes, mk = pattern.mask || "ff".repeat(b.length / 2);
  for (let i = 0; i < b.length; i += 2) {
    sig += (mk.substr(i, 2) === "00") ? "?? " : (b.substr(i, 2) + " ");
  }
  const hits = Memory.scanSync(m.base, m.size, sig.trim());
  if (hits.length === 0) return null;
  return { addr: hits[0].address, module: m.name, via: "pattern",
           hits: hits.length };
}

// 名前リストを順に試し、最初に解決できたものを返す。
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
  return r.name + " @ " + r.addr + " in " + r.module +
         " (rva 0x" + r.addr.sub(Process.findModuleByName(r.module).base).toString(16) +
         ", via " + r.via + ")";
}

// TARGET_SPEC は各 frida_*.py が _common.target_js() で宣言する。
// "OPUS" | "KRISP" | "AUTO" | ["明示名", ...]
function resolveTarget() {
  let names;
  if (Array.isArray(TARGET_SPEC)) names = TARGET_SPEC;
  else if (TARGET_SPEC === "OPUS") names = OPUS_SYMS;
  else if (TARGET_SPEC === "KRISP") names = KRISP_SYMS;
  else names = OPUS_SYMS.concat(KRISP_SYMS);

  // create/ctl/init は注入地点ではないので候補から外す。
  names = names.filter(function (n) { return !/create|ctl|init/i.test(n); });

  // ボイス関連モジュールを先に見て、外れたら全モジュールに広げる。
  const pri = voiceModules();
  const priNames = pri.map(function (m) { return m.name; });
  const rest = Process.enumerateModules().filter(function (m) {
    return priNames.indexOf(m.name) < 0;
  });
  return resolveAny(names, pri.concat(rest), PATTERNS);
}

// opus_encode_float / KrispNCProcessFloat は float32、それ以外は int16。
function isFloatTarget(name) { return /float/i.test(name); }
