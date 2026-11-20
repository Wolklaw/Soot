"""A stand-in for Spark Link's PC side, for testing Soot's Link to PC on an emulator or phone.

It is not the PC app (that lives in SparkOS, files/spark-link/). It speaks the same protocol
(docs/spark-link-protocol.md in the SparkOS repo, draft 2) with the same Python ssl settings, so a
phone that works with it should work with the real thing. Needs Python 3.10+ and the openssl
command (to make the PC's certificate once).

    python phone/tools/spark_link_pc.py --dir <state folder> [--bind 0.0.0.0] [--host <address in the link>]

It prints the pairing link (paste it into Soot, or open it with adb). Pairing is accepted on its own
unless --ask is given. While it runs, write commands to <state folder>/commands, one per line:

    url https://example.org      clip some text      file C:/path/to/file      ring
    unpair      hello url,files      (hello re-sends the PC's feature list)      quit

Everything the phone sends is printed; files land in <state folder>/received.
"""
import argparse
import base64
import hashlib
import json
import os
import secrets
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time

MAX = 1 << 20
FEATURES = ["url", "clipboard", "files", "notify", "battery", "ring"]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


class PC:
    def __init__(self, args):
        self.dir = args.dir
        os.makedirs(os.path.join(self.dir, "received"), exist_ok=True)
        self.key = os.path.join(self.dir, "key.pem")
        self.cert = os.path.join(self.dir, "cert.pem")
        if not os.path.exists(self.cert):
            subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
                            "-nodes", "-keyout", self.key, "-out", self.cert, "-days", "3650", "-subj", "/CN=testpc",
                            "-sha256"], check=True, capture_output=True)
        der = ssl.PEM_cert_to_DER_cert(open(self.cert).read())
        self.fp = hashlib.sha256(der).hexdigest()
        self.id = "testpc" + hashlib.sha256(der).hexdigest()[:10]
        self.name = args.name
        self.ask = args.ask
        self.features = list(FEATURES)
        self.paired_file = os.path.join(self.dir, "paired.json")
        self.paired = json.load(open(self.paired_file)) if os.path.exists(self.paired_file) else {}
        self.code = "%06d" % secrets.randbelow(1000000)
        self.conn = None
        self.next_id = 1
        self.wlock = threading.Lock()
        self.offers = {}
        self.incoming = {}
        self.link = (f"sparklink://pair?v=1&host={args.host}&port={args.port}&id={self.id}&fp={self.fp}"
                     f"&code={self.code}&name={self.name.replace(' ', '%20')}")

    def context(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_3
        ctx.load_cert_chain(self.cert, self.key)
        ctx.verify_mode = ssl.CERT_OPTIONAL
        ctx.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
        certs = [base64.b64decode(p["cert"]) for p in self.paired.values()]
        if certs:
            ctx.load_verify_locations(cadata=b"".join(certs))
        return ctx

    def save(self):
        json.dump(self.paired, open(self.paired_file, "w"), indent=1)

    # ---- frames ----

    def send(self, sock, m, binary=None):
        with self.wlock:
            if "id" not in m:
                m["id"] = self.next_id
                self.next_id += 1
            data = json.dumps(m).encode()
            out = struct.pack(">I", len(data)) + data
            if binary is not None:
                out += struct.pack(">I", len(binary)) + binary
            sock.sendall(out)
        if m.get("type") not in ("pong", "file.chunk"):
            log("->", json.dumps(m)[:200])
        return m["id"]

    @staticmethod
    def read_exact(sock, n):
        b = b""
        while len(b) < n:
            got = sock.recv(n - len(b))
            if not got:
                raise ConnectionError("closed")
            b += got
        return b

    def read_frame(self, sock, limit=MAX):
        (n,) = struct.unpack(">I", self.read_exact(sock, 4))
        if n > limit:
            raise ValueError(f"frame {n} over limit")
        return self.read_exact(sock, n)

    def read(self, sock):
        m = json.loads(self.read_frame(sock))
        if not isinstance(m, dict):
            raise ValueError("not an object")
        return m

    # ---- one connection ----

    def serve(self, raw, addr):
        try:
            sock = self.context().wrap_socket(raw, server_side=True)
        except ssl.SSLError as e:
            log("handshake refused from", addr, e.reason)
            raw.close()
            return
        der = sock.getpeercert(binary_form=True)
        try:
            if der is None:
                self.pairing(sock)
            else:
                self.session(sock, hashlib.sha256(der).hexdigest())
        except (ConnectionError, OSError, ValueError, ssl.SSLError) as e:
            log("connection ended:", e)
        finally:
            if self.conn is sock:
                self.conn = None
            sock.close()

    def pairing(self, sock):
        sock.settimeout(10)
        m = self.read(sock)
        log("<-", json.dumps({k: (v if k != "cert" else v[:24] + "...") for k, v in m.items()}))
        if m.get("type") != "pair":
            raise ValueError("unauthenticated connection sent something else than pair")
        if m.get("code") != self.code:
            self.send(sock, {"type": "pair.fail", "re": m.get("id"), "reason": "code"})
            return
        der = base64.b64decode(m["cert"], validate=True)
        fp = hashlib.sha256(der).hexdigest()
        dev = m.get("device", {})
        # the cert must be one OpenSSL will take as its own trust anchor
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_verify_locations(cadata=der)
        if self.ask:
            ok = input(f"Pair with {dev.get('name')}? [y/N] ").strip().lower() == "y"
        else:
            log("pairing with", dev.get("name"), "phone fp", fp[:16])
            ok = True
        if not ok:
            self.send(sock, {"type": "pair.fail", "re": m.get("id"), "reason": "declined"})
            return
        self.paired[fp] = {"id": dev.get("id"), "name": dev.get("name"), "cert": m["cert"]}
        self.save()
        self.send(sock, {"type": "pair.ok", "re": m.get("id"), "device": {"id": self.id, "name": self.name, "kind": "pc"}})
        log("PAIRED", dev.get("name"))

    def session(self, sock, fp):
        if fp not in self.paired:
            raise ValueError("certificate not paired")
        sock.settimeout(75)
        hello = self.read(sock)
        log("<-", json.dumps(hello))
        if hello.get("type") != "hello" or hello.get("device", {}).get("id") != self.paired[fp]["id"]:
            raise ValueError("bad hello")
        self.conn = sock
        self.phone_fp = fp
        self.send(sock, {"type": "hello", "v": 1, "device": {"id": self.id, "name": self.name, "kind": "pc"}, "features": self.features})
        log("CONNECTED", self.paired[fp]["name"])
        while True:
            m = self.read(sock)
            t = m.get("type")
            if t == "ping":
                self.send(sock, {"type": "pong", "re": m["id"]})
                log("<- ping")
                continue
            if t == "file.chunk":
                data = self.read_frame(sock, 65536)
                f = self.incoming.get(m["file"])
                if f is None:
                    continue
                f["out"].write(data)
                f["md"].update(data)
                f["got"] += len(data)
                if m.get("last"):
                    f["out"].close()
                    ok = f["got"] == f["size"] and f["md"].hexdigest() == f["sha"]
                    log("FILE", f["path"], "ok" if ok else "BAD", f["got"], "bytes")
                    self.send(sock, {"type": "file.done", "re": m["file"], "ok": ok})
                    del self.incoming[m["file"]]
                continue
            log("<-", json.dumps(m)[:300])
            if t == "file.offer":
                name = os.path.basename(m["name"].replace("\\", "/")).lstrip(".") or "file"
                path = os.path.join(self.dir, "received", name)
                self.incoming[m["id"]] = {"out": open(path, "wb"), "md": hashlib.sha256(), "got": 0,
                                          "size": m["size"], "sha": m["sha256"], "path": path}
                self.send(sock, {"type": "file.accept", "re": m["id"]})
            elif t in ("file.accept", "file.reject", "file.done"):
                ev = self.offers.get(m.get("re"))
                if ev:
                    ev["answer"] = m
                    ev["event"].set()
            elif t == "unpair":
                del self.paired[fp]
                self.save()
                log("the phone unpaired")
                return

    # ---- commands ----

    def command(self, line):
        cmd, _, arg = line.strip().partition(" ")
        sock = self.conn
        if cmd == "quit":
            os._exit(0)
        if sock is None:
            log("no phone connected; ignoring", cmd)
            return
        if cmd == "url":
            self.send(sock, {"type": "url.open", "url": arg})
        elif cmd == "clip":
            self.send(sock, {"type": "clipboard.set", "text": arg})
        elif cmd == "ring":
            self.send(sock, {"type": "ring"})
        elif cmd == "unpair":
            self.send(sock, {"type": "unpair"})
            self.paired.pop(self.phone_fp, None)
            self.save()
            sock.close()
        elif cmd == "hello":
            self.features = [f for f in arg.split(",") if f]
            self.send(sock, {"type": "hello", "v": 1, "device": {"id": self.id, "name": self.name, "kind": "pc"}, "features": self.features})
        elif cmd == "file":
            threading.Thread(target=self.send_file, args=(sock, arg), daemon=True).start()
        elif cmd == "forget":          # drop the phone without telling it, like a reset PC
            self.paired.clear()
            self.save()
            sock.close()

    def send_file(self, sock, path):
        data = open(path, "rb").read()
        ev = {"event": threading.Event()}
        oid = self.next_id
        self.offers[oid] = ev
        self.send(sock, {"type": "file.offer", "id": oid, "name": os.path.basename(path), "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        with self.wlock:
            self.next_id = max(self.next_id, oid + 1)
        if not ev["event"].wait(130):
            log("no answer to the offer")
            return
        if ev["answer"]["type"] != "file.accept":
            log("offer refused:", ev["answer"])
            return
        ev["event"].clear()
        seq = 0
        chunks = [data[i:i + 65536] for i in range(0, len(data), 65536)] or [b""]
        for i, c in enumerate(chunks):
            self.send(sock, {"type": "file.chunk", "file": oid, "seq": seq, "last": i == len(chunks) - 1}, binary=c)
            seq += 1
        ev["event"].wait(60)
        log("file sent, phone said", ev.get("answer"))

    def watch_commands(self):
        path = os.path.join(self.dir, "commands")
        open(path, "a").close()
        while True:
            time.sleep(0.5)
            try:
                lines = open(path, encoding="utf-8").read().splitlines()
            except OSError:
                continue
            if lines:
                open(path, "w").close()
                for line in lines:
                    if line.strip():
                        self.command(line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=47100)
    ap.add_argument("--host", default="10.0.2.2", help="address the phone uses (10.0.2.2 is this PC, seen from an emulator)")
    ap.add_argument("--name", default="Test PC")
    ap.add_argument("--ask", action="store_true")
    args = ap.parse_args()
    pc = PC(args)
    log("fp", pc.fp)
    log("key check", " ".join(pc.fp[i:i + 4].upper() for i in range(0, 16, 4)))
    log("code", pc.code)
    log("link", pc.link)
    open(os.path.join(args.dir, "link.txt"), "w").write(pc.link)
    threading.Thread(target=pc.watch_commands, daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.bind, args.port))
    srv.listen(8)
    log("listening on", args.bind, args.port)
    while True:
        raw, addr = srv.accept()
        threading.Thread(target=pc.serve, args=(raw, addr), daemon=True).start()


if __name__ == "__main__":
    main()
