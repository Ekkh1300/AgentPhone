#!/usr/bin/env python3
"""Local OpenAI-compatible mock server used for AgentPhone end-to-end verification.

Run: python tools/mock-server.py
Base URL for the Android emulator: http://10.0.2.2:8765/v1
"""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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
        user_text = ""
        for message in reversed(messages):
            if message.get("role") == "user":
                content = message.get("content")
                if isinstance(content, str):
                    user_text = content
                elif isinstance(content, list):
                    user_text = " ".join(
                        part.get("text", "") for part in content if isinstance(part, dict)
                    )
                break
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        wants_tool = (
            ("file" in user_text.lower() or "فایل" in user_text)
            and not has_tool_result
            and bool(request.get("tools"))
        )
        print(
            "[mock] stream=", stream,
            "max_tokens=", request.get("max_tokens"),
            "tools=", len(request.get("tools", [])),
            "last_user=", user_text[:120].replace("\n", " "),
            flush=True,
        )

        if not stream:
            if wants_tool:
                message = {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "call_mock_1",
                            "type": "function",
                            "function": {
                                "name": "files_write",
                                "arguments": json.dumps(
                                    {
                                        "location": "workspace",
                                        "path": "mock-note.md",
                                        "content": "# Hello from the mock provider\n\nThis file was created by an agent tool call.\n",
                                    }
                                ),
                            },
                        }
                    ],
                }
                finish = "tool_calls"
            else:
                message = {
                    "role": "assistant",
                    "content": "This is the mock provider. Streaming is working.",
                }
                finish = "stop"
            self.send_json(
                200,
                {
                    "id": "chatcmpl-mock",
                    "object": "chat.completion",
                    "model": request.get("model", "mock-model"),
                    "choices": [
                        {"index": 0, "message": message, "finish_reason": finish}
                    ],
                    "usage": {
                        "prompt_tokens": 12,
                        "completion_tokens": 8,
                        "total_tokens": 20,
                    },
                },
            )
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        if wants_tool:
            args = json.dumps(
                {
                    "location": "workspace",
                    "path": "mock-note.md",
                    "content": "# Hello from the mock provider\n\nThis file was created by an agent tool call.\n",
                }
            )
            self.wfile.write(
                chunk(
                    {
                        "role": "assistant",
                        "tool_calls": [
                            {
                                "index": 0,
                                "id": "call_mock_1",
                                "type": "function",
                                "function": {"name": "files_write", "arguments": args},
                            }
                        ],
                    }
                ).encode()
            )
            self.wfile.flush()
            time.sleep(0.3)
            self.wfile.write(
                chunk({"role": "assistant"}, finish="tool_calls").encode()
            )
        else:
            text = (
                "Hello from the mock provider. I can stream text, call tools, "
                "and render **Markdown**.\n\n```kotlin\nval answer = 42\n```\n\n"
                "| Tool | Status |\n| --- | --- |\n| files_write | ready |\n"
            )
            words = text.split(" ")
            self.wfile.write(chunk({"role": "assistant", "content": ""}).encode())
            for index, word in enumerate(words):
                piece = word + (" " if index < len(words) - 1 else "")
                self.wfile.write(chunk({"content": piece}).encode())
                self.wfile.flush()
                time.sleep(0.015)
            self.wfile.write(
                chunk({}, finish="stop", usage={
                    "prompt_tokens": 42,
                    "completion_tokens": 31,
                    "total_tokens": 73,
                }).encode()
            )

        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    print(f"OpenAI mock listening on http://0.0.0.0:{PORT}/v1", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
