"""Listeners outside the sandbox for in-container.sh: TCP 19001, UDP 19002 (logs what arrives),
a unix socket on a path and an abstract one. Writes its pid, then signals readiness."""
import os
import socket
import threading
import time

WORK = "/work"


def accept_forever(server):
    while True:
        connection, _ = server.accept()
        connection.close()


def tcp():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 19001))
    server.listen(64)
    accept_forever(server)


def udp():
    server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server.bind(("127.0.0.1", 19002))
    while True:
        data, _ = server.recvfrom(100)
        with open(WORK + "/udp-received", "ab") as log:
            log.write(data + b"\n")


def unix(address):
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(address)
    server.listen(16)
    accept_forever(server)


for target, args in ((tcp, ()), (udp, ()), (unix, (WORK + "/out/unix.sock",)), (unix, ("\0llprobe-abstract",))):
    threading.Thread(target=target, args=args, daemon=True).start()

time.sleep(0.5)
with open(WORK + "/listeners.pid", "w") as pid:
    pid.write(str(os.getpid()))
while True:
    time.sleep(3600)
