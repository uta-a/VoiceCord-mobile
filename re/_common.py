# frida 接続とメッセージループの共通部。
# 移植元の ../VoiceCord/re/frida_*.py は Windows ローカル固定
# (get_local_device().attach(pid)) だったが、Android は USB 越しになり、
# さらに非root では frida-server が置けず gadget にアタッチする必要がある。
# その差分をここに集約し、各スクリプトは JS と引数だけを持つ。
import argparse
import io
import json
import os
import sys
import time

import frida

PACKAGE = "com.discord"


def add_common_args(ap):
    ap.add_argument("--mode", choices=("gadget", "server", "remote"), default="gadget",
                    help="gadget=非root(APK に frida-gadget 同梱), "
                         "server=root(frida-server 常駐), "
                         "remote=adb forward 経由 (既定: gadget)")
    ap.add_argument("--target", default=None,
                    help="アタッチ先。既定は mode により Gadget / com.discord")
    ap.add_argument("--spawn", action="store_true",
                    help="server モードで起動から掴む(初期化前のフックが要るとき)")
    ap.add_argument("--host", default="127.0.0.1:27042",
                    help="remote モードの接続先 (既定: 127.0.0.1:27042)")
    ap.add_argument("--log", default=None, help="ログを書き出すファイル")
    ap.add_argument("--seconds", type=int, default=0,
                    help="この秒数で終了する。0 は Ctrl-C まで継続 (既定: 0)")
    return ap


def get_device(args):
    if args.mode == "remote":
        # adb forward tcp:27042 tcp:27042 を先に実行しておくこと。
        return frida.get_device_manager().add_remote_device(args.host)
    return frida.get_usb_device(timeout=10)


def attach(args):
    """デバイスに接続し (session, pid) を返す。pid は spawn 時のみ非 None。"""
    device = get_device(args)
    target = args.target
    pending_pid = None

    if args.mode == "server" and args.spawn:
        pid = device.spawn([target or PACKAGE])
        session = device.attach(pid)
        pending_pid = pid
    else:
        if target is None:
            # gadget は listen モードで "Gadget" という名前で現れる。
            target = "Gadget" if args.mode in ("gadget", "remote") else PACKAGE
        session = device.attach(target)

    print("[+] attached: mode=%s target=%s" % (args.mode, target or PACKAGE))
    return session, pending_pid


def run(args, js, on_payload=None):
    """JS を流し込み、send() を受けてログする。

    on_payload(payload, data) を渡すと、行表示の代わりに独自処理ができる。
    """
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    logf = open(args.log, "w", encoding="utf-8") if args.log else None

    def emit(text):
        print(text)
        if logf:
            logf.write(text + "\n")
            logf.flush()

    session, pending_pid = attach(args)
    script = session.create_script(js)

    def on_message(msg, data):
        if msg.get("type") == "send":
            payload = msg["payload"]
            if on_payload is not None:
                on_payload(payload, data, emit)
            elif isinstance(payload, dict) and "line" in payload:
                emit(payload["line"])
            else:
                emit(str(payload))
        else:
            emit("[err] " + str(msg))

    script.on("message", on_message)
    script.load()

    if pending_pid is not None:
        frida.get_usb_device().resume(pending_pid)

    try:
        if args.seconds > 0:
            time.sleep(args.seconds)
        else:
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        emit("[+] 終了")
    finally:
        if logf:
            logf.close()
    return script


def load_resolve_js(patterns_path=None, version=None):
    """_resolve.js と patterns.json を、各スクリプトの JS の前に置く形で返す。

    patterns.json はトップレベルが Discord バージョン。キーが1つなら自動で選ぶ。
    "_" 始まりのキー(_readme/_example)は説明用なので無視する。
    """
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "_resolve.js"), encoding="utf-8") as fp:
        resolve = fp.read()

    patterns = {}
    if patterns_path and os.path.exists(patterns_path):
        with open(patterns_path, encoding="utf-8") as fp:
            doc = json.load(fp)
        versions = {k: v for k, v in doc.items() if not k.startswith("_")}
        if version:
            patterns = versions.get(version, {})
            if not patterns:
                print("[!] patterns.json にバージョン %s が無い。候補: %s"
                      % (version, ", ".join(versions) or "(なし)"))
        elif len(versions) == 1:
            only = next(iter(versions))
            patterns = versions[only]
            print("[+] patterns.json: バージョン %s を使用" % only)
        elif len(versions) > 1:
            print("[!] patterns.json に複数バージョンがある。--discord-version で指定すること: %s"
                  % ", ".join(versions))

    return resolve + chr(10) + "const PATTERNS = " + json.dumps(patterns) + ";" + chr(10)


def add_target_args(ap):
    """フック対象の指定。既定は Opus と Krisp の両方を試す。"""
    ap.add_argument("--family", choices=("opus", "krisp", "auto"), default="auto",
                    help="opus=Opus のみ, krisp=Krisp のみ, auto=両方試す (既定: auto)")
    ap.add_argument("--symbol", default=None,
                    help="シンボル名を直接指定する(候補リストを飛ばす)")
    ap.add_argument("--patterns", default=None,
                    help="signatures/patterns.json のパス")
    ap.add_argument("--discord-version", default=None,
                    help="patterns.json 内のバージョンキー(複数ある場合に必要)")
    return ap


def target_js(args):
    """--family / --symbol を JS 側の定数宣言に変換する。"""
    if args.symbol:
        names = [args.symbol]
    elif args.family == "opus":
        names = "OPUS"
    elif args.family == "krisp":
        names = "KRISP"
    else:
        names = "AUTO"
    return "const TARGET_SPEC = " + json.dumps(names) + ";\n"


def parser(description):
    return add_common_args(argparse.ArgumentParser(description=description))
