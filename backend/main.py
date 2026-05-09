"""
Vertext Live — Self-hosted WebRTC Signaling Server
No third-party API keys needed. Runs entirely on Render.
Flow:
  1. Android streamer connects → generates 8-digit PIN
  2. Web viewer enters PIN → joins same room
  3. Server relays WebRTC offer/answer/ICE between them
"""
import os, json, random, string, time
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

app = FastAPI(title="Vertext Live")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# { pin: { "streamer": WebSocket | None, "viewers": [WebSocket], "room": str, "expires": float } }
rooms: dict = {}


def clean_expired():
    now = time.time()
    for pin in [k for k, v in rooms.items() if v["expires"] < now]:
        del rooms[pin]


def make_pin() -> str:
    for _ in range(30):
        pin = "".join(random.choices(string.digits, k=8))
        if pin not in rooms:
            return pin
    return "".join(random.choices(string.digits, k=8))


# ── REST ─────────────────────────────────────────────────────────────

class GenerateRequest(BaseModel):
    room_name: str

@app.post("/pin/generate")
def generate_pin(req: GenerateRequest):
    clean_expired()
    pin = make_pin()
    rooms[pin] = {
        "streamer": None,
        "viewers": [],
        "room": req.room_name,
        "expires": time.time() + 86400,
    }
    return {"pin": pin, "room": req.room_name, "expires_in": "24 hours"}


@app.post("/pin/validate")
def validate_pin(body: dict):
    clean_expired()
    pin = body.get("pin", "")
    if pin not in rooms:
        raise HTTPException(status_code=404, detail="Invalid or expired PIN")
    return {"valid": True, "room": rooms[pin]["room"]}


@app.get("/health")
def health():
    return {"status": "ok", "active_rooms": len(rooms)}


# ── WebSocket Signaling ───────────────────────────────────────────────

@app.websocket("/ws/{pin}/{role}")
async def websocket_endpoint(ws: WebSocket, pin: str, role: str):
    """
    role = "streamer" or "viewer"
    Messages are JSON: { "type": "offer"|"answer"|"ice"|"ready"|"ping" }
    """
    await ws.accept()

    if pin not in rooms:
        await ws.send_json({"type": "error", "message": "Invalid PIN"})
        await ws.close()
        return

    room = rooms[pin]

    if role == "streamer":
        room["streamer"] = ws
        await ws.send_json({"type": "connected", "role": "streamer", "pin": pin})
        # Notify any waiting viewers
        for viewer in room["viewers"]:
            try:
                await viewer.send_json({"type": "streamer_joined"})
            except Exception:
                pass
    else:
        room["viewers"].append(ws)
        await ws.send_json({"type": "connected", "role": "viewer", "pin": pin})
        # Tell streamer a new viewer arrived
        if room["streamer"]:
            try:
                await room["streamer"].send_json({"type": "viewer_joined"})
            except Exception:
                pass

    try:
        while True:
            data = await ws.receive_json()
            msg_type = data.get("type")

            if role == "streamer":
                # Streamer → broadcast to all viewers
                dead = []
                for viewer in room["viewers"]:
                    try:
                        await viewer.send_json(data)
                    except Exception:
                        dead.append(viewer)
                for d in dead:
                    room["viewers"].remove(d)

            else:
                # Viewer → send to streamer
                if room["streamer"]:
                    try:
                        await room["streamer"].send_json(data)
                    except Exception:
                        room["streamer"] = None

    except WebSocketDisconnect:
        if role == "streamer":
            room["streamer"] = None
            for viewer in room["viewers"]:
                try:
                    await viewer.send_json({"type": "streamer_left"})
                except Exception:
                    pass
        else:
            if ws in room["viewers"]:
                room["viewers"].remove(ws)


# Serve web viewer — must be last
app.mount("/", StaticFiles(directory="web", html=True), name="web")
