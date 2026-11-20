"""Unit tests for the NFCReader PC-side receiver (reader.py).

These tests verify protocol handling, JSON parsing, scan logging, and
argument parsing without requiring an actual Android device.
"""

import json
import os
import sys
import tempfile
import threading
import time
from unittest.mock import MagicMock, patch

import pytest

# Ensure the project root is on the path so `reader` can be imported.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import reader  # noqa: E402


class TestSend:
    """Tests for the send() function."""

    def test_send_when_connected(self):
        mock_sock = MagicMock()
        reader.sock = mock_sock
        reader.send("SCAN")
        mock_sock.sendall.assert_called_once_with(b"SCAN\n")

    def test_send_when_not_connected(self):
        reader.sock = None
        # Should not raise; just log an error.
        reader.send("SCAN")

    def test_send_on_broken_pipe(self):
        mock_sock = MagicMock()
        mock_sock.sendall.side_effect = BrokenPipeError()
        reader.sock = mock_sock
        # Should not raise; error is caught and logged.
        reader.send("SCAN")


class TestSaveScan:
    """Tests for save_scan()."""

    def test_save_scan_writes_jsonl(self):
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".jsonl", delete=False
        ) as tmp:
            tmp_path = tmp.name

        try:
            data = {"event": "scan", "uid": "AABBCCDD"}
            reader.save_scan(data, tmp_path)

            with open(tmp_path, "r", encoding="utf-8") as f:
                line = f.readline().strip()

            saved = json.loads(line)
            assert saved["event"] == "scan"
            assert saved["uid"] == "AABBCCDD"
            assert "_pc_time" in saved
        finally:
            os.unlink(tmp_path)

    def test_save_scan_creates_file_if_missing(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            log_path = os.path.join(tmpdir, "new_scans.jsonl")
            assert not os.path.exists(log_path)

            reader.save_scan({"event": "scan", "uid": "001122"}, log_path)

            assert os.path.exists(log_path)
            with open(log_path, "r", encoding="utf-8") as f:
                saved = json.loads(f.readline().strip())
            assert saved["uid"] == "001122"


class TestParseArgs:
    """Tests for CLI argument parsing."""

    def test_defaults(self):
        with patch.object(sys, "argv", ["reader.py", "--auth-token", "tok"]):
            args = reader.parse_args()
            assert args.host == reader.DEFAULT_HOST
            assert args.port == reader.DEFAULT_PORT
            assert args.log_file == reader.DEFAULT_LOG_FILE
            assert args.auth_token == "tok"

    def test_custom_values(self):
        with patch.object(
            sys,
            "argv",
            ["reader.py", "--host", "192.168.1.50", "--port", "9999", "--auth-token", "abc"],
        ):
            args = reader.parse_args()
            assert args.host == "192.168.1.50"
            assert args.port == 9999
            assert args.auth_token == "abc"

    def test_missing_auth_token(self):
        with patch.object(sys, "argv", ["reader.py"]):
            with pytest.raises(SystemExit):
                reader.parse_args()


class TestReceiverEventDispatch:
    """Tests for the receiver's event dispatch logic."""

    def test_receiver_handles_auth_required(self):
        events = []
        mock_sock = MagicMock()
        mock_file = MagicMock()
        mock_file.readline.side_effect = [
            json.dumps({"event": "auth_required"}) + "\n",
            "",  # EOF to stop loop
        ]
        mock_sock.makefile.return_value = mock_file

        reader.running = True
        try:
            reader.receiver(mock_sock, "/tmp/test.jsonl")
        finally:
            reader.running = True  # reset for other tests

    def test_receiver_handles_scan_event(self):
        lines_captured = []

        def fake_readline():
            return json.dumps(
                {"event": "scan", "uid": "AABB", "technologies": ["NfcA"]}
            ) + "\n"

        mock_sock = MagicMock()
        mock_file = MagicMock()
        mock_file.readline.side_effect = [fake_readline(), ""]
        mock_sock.makefile.return_value = mock_file

        reader.running = True
        try:
            with patch.object(reader, "save_scan") as mock_save:
                reader.receiver(mock_sock, "/tmp/test.jsonl")
                mock_save.assert_called_once()
        finally:
            reader.running = True


class TestSocketTimeout:
    """Tests that socket timeouts are configured."""

    @patch("socket.create_connection")
    def test_connect_timeout_applied(self, mock_create):
        mock_sock = MagicMock()
        mock_create.return_value = mock_sock

        with patch.object(sys, "argv", ["reader.py", "--auth-token", "tok"]):
            with patch.object(reader, "receiver"):
                with patch.object(reader, "send"):
                    with patch("builtins.input", return_value="quit"):
                        try:
                            reader.main()
                        except SystemExit:
                            pass

        mock_create.assert_called_once()
        call_args = mock_create.call_args
        assert call_args.kwargs["timeout"] == reader.CONNECT_TIMEOUT
        mock_sock.settimeout.assert_called_with(reader.SOCKET_READ_TIMEOUT)
