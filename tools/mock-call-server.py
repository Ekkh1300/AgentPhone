#!/usr/bin/env python3
"""Command-driven OpenAI mock used for AgentPhone end-to-end verification.

Run: python tools/mock-call-server.py
Base URL for the Android emulator: http://10.0.2.2:8765/v1

Send `CALL <tool_name> <json-arguments>` as the chat message and the server emits
exactly that tool call. The next request prints the tool result to stdout, so the
test can be verified from the server log.
"""
import json
import shlex
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

PORT = 8765


def chunk(delta, finish=None, usage=None):
    payload = {
        "id": "chatcmpl-mock",
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": "mock-model",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }
    if usage is not None:
        payload["usage"] = usage
    return "data: " + json.dumps(payload) + "\n\n"


def last_user_text(messages):
    for message in reversed(messages):
        if message.get("role") == "user":
            content = message.get("content")
            if isinstance(content, str):
                return content
            if isinstance(content, list):
                return " ".join(
                    part.get("text", "") for part in content if isinstance(part, dict)
                )
            return ""
    return ""


def parse_call(text):
    text = text.strip()
    if not text.upper().startswith("CALL "):
        return None
    rest = text[5:].strip()
    if not rest:
        return None
    name, _, raw = rest.partition(" ")
    raw = raw.strip() or "{}"
    if raw.startswith("{"):
        try:
            return name.strip(), json.loads(raw)
        except json.JSONDecodeError:
            return name.strip(), {"_raw": raw}
    args = {}
    for token in shlex.split(raw):
        key, sep, value = token.partition("=")
        if not sep:
            continue
        if value.lower() in ("true", "false"):
            args[key] = value.lower() == "true"
        elif value.lstrip("-").isdigit():
            args[key] = int(value)
        else:
            args[key] = value
    return name.strip(), args


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[mock]", fmt % args, flush=True)

    def send_json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") == "/v1/models":
            self.send_json(
                200,
                {
                    "object": "list",
                    "data": [
                        {"id": "mock-model", "object": "model", "owned_by": "agentphone"},
                        {"id": "mock-vision", "object": "model", "owned_by": "agentphone"},
                    ],
                },
            )
        else:
            self.send_json(404, {"error": {"message": "Not found"}})

    def do_POST(self):
        if self.path.rstrip("/") != "/v1/chat/completions":
            self.send_json(404, {"error": {"message": "Not found"}})
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        messages = request.get("messages", [])
        stream = bool(request.get("stream"))
        user_text = last_user_text(messages)
        results = [m for m in messages if m.get("role") == "tool"]
        last_user_index = max(
            (i for i, m in enumerate(messages) if m.get("role") == "user"),
            default=-1,
        )
        turn_results = [
            m for m in messages[last_user_index + 1:] if m.get("role") == "tool"
        ]
        call = parse_call(user_text)
        print(
            "[mock] stream=", stream,
            "tools=", len(request.get("tools", [])),
            "last_user=", user_text[:160].replace("\n", " "),
            flush=True,
        )
        for result in results[-3:]:
            content = result.get("content")
            if not isinstance(content, str):
                content = json.dumps(content)
            print(
                "[tool-result]", result.get("tool_call_id"),
                content[:4000].replace("\n", "\\n"),
                flush=True,
            )

        want_tool = call is not None and not turn_results
        if not stream:
            if want_tool:
                name, args = call
                message = {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "call_mock_1",
                            "type": "function",
                            "function": {"name": name, "arguments": json.dumps(args)},
                        }
                    ],
                }
                finish = "tool_calls"
            else:
                tail = ""
                if turn_results:
                    content = turn_results[-1].get("content")
                    tail = content if isinstance(content, str) else json.dumps(content)
                message = {
                    "role": "assistant",
                    "content": ("Done. " + tail[:500]) if tail else
                    "This is the mock provider. Streaming is working.",
                }
                finish = "stop"
            self.send_json(
                200,
                {
                    "id": "chatcmpl-mock",
                    "object": "chat.completion",
                    "model": request.get("model", "mock-model"),
                    "choices": [{"index": 0, "message": message, "finish_reason": finish}],
                    "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
                },
            )
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        if want_tool:
            name, args = call
            self.wfile.write(
                chunk(
                    {
                        "role": "assistant",
                        "tool_calls": [
                            {
                                "index": 0,
                                "id": "call_mock_1",
                                "type": "function",
                                "function": {"name": name, "arguments": json.dumps(args)},
                            }
                        ],
                    }
                ).encode()
            )
            self.wfile.flush()
            time.sleep(0.2)
            self.wfile.write(chunk({"role": "assistant"}, finish="tool_calls").encode())
        else:
            tail = ""
            if turn_results:
                content = turn_results[-1].get("content")
                tail = content if isinstance(content, str) else json.dumps(content)
            text = ("Done. " + tail[:800]) if tail else "Mock provider: no tool call requested."
            self.wfile.write(chunk({"role": "assistant", "content": ""}).encode())
            for word in text.split(" "):
                self.wfile.write(chunk({"content": word + " "}).encode())
                self.wfile.flush()
                time.sleep(0.01)
            self.wfile.write(chunk({}, finish="stop", usage={
                "prompt_tokens": 42, "completion_tokens": 31, "total_tokens": 73,
            }).encode())

        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    print(f"OpenAI mock listening on http://0.0.0.0:{PORT}/v1", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
