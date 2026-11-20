#!/usr/bin/env python3
"""NFCReader PC-side receiver.

Connects to the Android NFCReader app over TCP (port 18765, typically via
`adb forward tcp:18765 tcp:18765`), sends commands (SCAN, WRITE_TEXT, WRITE_URI,
CANCEL_WRITE, PING, quit), receives JSON events, and logs scans to scans.jsonl.
"""

import argparse
import json
import logging
import socket
import sys
import threading
from datetime import datetime, timezone

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18765
DEFAULT_LOG_FILE = "scans.jsonl"
CONNECT_TIMEOUT = 10.0
SOCKET_READ_TIMEOUT = 30.0

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("nfc-reader")

sock: socket.socket | None = None
running = True
receiver_thread: threading.Thread | None = None


def send(command: str) -> None:
    """Send a command to the Android app."""
    global sock
    if sock is None:
        log.error("Not connected — cannot send command: %s", command)
        return
    try:
        sock.sendall((command + "\n").encode("utf-8"))
    except (OSError, BrokenPipeError) as e:
        log.error("Send error: %s", e)


def save_scan(data: dict, log_file: str) -> None:
    """Append a scan event to the JSONL log file."""
    data["_pc_time"] = datetime.now(timezone.utc).isoformat()
    try:
        with open(log_file, "a", encoding="utf-8") as f:
            f.write(json.dumps(data, ensure_ascii=False) + "\n")
    except OSError as e:
        log.error("Failed to write scan log: %s", e)


def print_scan(data: dict) -> None:
    """Pretty-print a scan event to the console."""
    print()
    print("=" * 60)
    print("NFC TAG")
    print("=" * 60)
    print("UID:", data.get("uid"))

    technologies = data.get("technologies", [])
    print("Technologies:")
    for tech in technologies:
        print("  -", tech)

    if "type" in data:
        print("NDEF type:", data["type"])
    if "maxSize" in data:
        print("NDEF size:", data["maxSize"])
    if "writable" in data:
        print("Writable:", data["writable"])

    for record in data.get("ndefRecords", []):
        print()
        print("Record:")
        print("  TNF:", record.get("tnf"))
        print("  Type:", record.get("type"))
        print("  Payload:", record.get("payloadHex"))
        if "uri" in record:
            print("  URI:", record["uri"])

    print("=" * 60)
    print()


def receiver(sock: socket.socket, log_file: str) -> None:
    """Read JSON lines from the socket and dispatch to handlers."""
    global running

    file_obj = sock.makefile("r", encoding="utf-8")

    try:
        while running:
            line = file_obj.readline()
            if not line:
                log.warning("Android disconnected")
                running = False
                break

            line = line.strip()
            if not line:
                continue

            try:
                data = json.loads(line)
            except json.JSONDecodeError:
                log.warning("Invalid JSON: %s", line)
                continue

            event = data.get("event")

            if event == "auth_required":
                log.warning("Android requires authentication — send AUTH|<token>")
            elif event == "auth_ok":
                log.info("Authentication accepted by Android")
            elif event == "auth_failed":
                log.error("Authentication failed: %s", data.get("reason", "unknown"))
            elif event == "scan":
                print_scan(data)
                save_scan(data, log_file)
            elif event == "scan_ready":
                log.info("Scan mode ready — touch an NFC tag")
            elif event == "write_result":
                log.info("[WRITE] %s", data.get("result"))
            elif event == "write_ready":
                log.info(
                    "[+] Write armed: type=%s data=%s",
                    data.get("type"),
                    repr(data.get("data")),
                )
            elif event == "write_cancelled":
                log.info("[*] Write cancelled")
            elif event == "pong":
                log.info("[+] Android: PONG")
            else:
                log.info("[ANDROID] %s", data)

    except Exception as e:
        if running:
            log.error("Receiver error: %s", e)
    finally:
        try:
            file_obj.close()
        except Exception:
            pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="NFCReader PC client")
    parser.add_argument(
        "--host", default=DEFAULT_HOST, help=f"Android host (default: {DEFAULT_HOST})"
    )
    parser.add_argument(
        "--port", type=int, default=DEFAULT_PORT, help=f"TCP port (default: {DEFAULT_PORT})"
    )
    parser.add_argument(
        "--log-file", default=DEFAULT_LOG_FILE, help=f"Scan log file (default: {DEFAULT_LOG_FILE})"
    )
    parser.add_argument(
        "--auth-token", required=True, help="Authentication token for the TCP connection"
    )
    return parser.parse_args()


def main() -> None:
    global sock, running, receiver_thread

    args = parse_args()

    log.info("Connecting to Android on %s:%s...", args.host, args.port)

    try:
        sock = socket.create_connection((args.host, args.port), timeout=CONNECT_TIMEOUT)
        sock.settimeout(SOCKET_READ_TIMEOUT)
    except OSError as e:
        log.error("Connection failed: %s", e)
        sys.exit(1)

    log.info("Connected. Sending authentication...")
    send(f"AUTH|{args.auth_token}")

    receiver_thread = threading.Thread(
        target=receiver, args=(sock, args.log_file), daemon=True, name="nfc-receiver"
    )
    receiver_thread.start()

    print()
    print("Commands:")
    print("  scan")
    print("  text <text>")
    print("  uri <url>")
    print("  cancel")
    print("  ping")
    print("  quit")
    print()

    try:
        while running:
            try:
                command = input("> ").strip()
            except (KeyboardInterrupt, EOFError):
                command = "quit"

            if not command:
                continue

            if command == "scan":
                send("SCAN")
            elif command.startswith("text "):
                text = command[5:]
                if not text:
                    print("[!] Empty text")
                    continue
                send("WRITE_TEXT|" + text)
                print("[*] Write mode enabled. Touch your writable NDEF tag.")
            elif command.startswith("uri "):
                uri = command[4:]
                if not uri:
                    print("[!] Empty URI")
                    continue
                send("WRITE_URI|" + uri)
                print("[*] URI write mode enabled. Touch your writable NDEF tag.")
            elif command == "cancel":
                send("CANCEL_WRITE")
            elif command == "ping":
                send("PING")
            elif command == "quit":
                running = False
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()
                break
            else:
                print("[!] Unknown command")
    except (KeyboardInterrupt, EOFError):
        running = False
    finally:
        running = False
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        log.info("Bye")


if __name__ == "__main__":
    main()
