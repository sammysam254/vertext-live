"""
Vertext Live — Token Server
Streamers generate an 8-digit PIN in the Android app.
Viewers just enter the PIN on the public web page — no API key needed.
"""
import os, time, random, string
from fastapi import FastAPI, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from livekit import api

app = FastAPI(title="Vertext Live API")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

LIVEKIT_API_KEY    = os.environ["LIVEKIT_API_KEY"]
LIVEKIT_API_SECRET = os.environ["LIVEKIT_API_SECRET"]
LIVEKIT_URL        = os.environ["LIVEKIT_URL"]

# In-memory PIN store { pin: { room, expires_at } }
pin_store: dict = {}


def _clean_expired():
    now = time.time()
    for p in [k for k, v in pin_store.items() if v["expires_at"] < now]:
        del pin_store[p]


def _make_token(room: str, identity: str, can_publish: bool) -> str:
    at = api.AccessToken(LIVEKIT_API_KEY, LIVEKIT_API_SECRET)
    at.with_identity(identity)
    at.with_name(identity)
    at.with_grants(api.VideoGrants(
        room_join=True, room=room,
        can_publish=can_publish,
        can_subscribe=True,
        can_publish_data=can_publish,
    ))
    at.with_ttl(7200)
    return at.to_jwt()


# ── Models ────────────────────────────────────────────────────────────

class GeneratePinRequest(BaseModel):
    room_name: str

class WatchRequest(BaseModel):
    pin: str
    viewer_name: str = "viewer"


# ── Routes ────────────────────────────────────────────────────────────

@app.post("/pin/generate")
def generate_pin(req: GeneratePinRequest):
    """Android app calls this → gets 8-digit PIN + publisher LiveKit token."""
    _clean_expired()
    for _ in range(30):
        pin = "".join(random.choices(string.digits, k=8))
        if pin not in pin_store:
            break
    pin_store[pin] = {
        "room": req.room_name,
        "expires_at": time.time() + 86400,
    }
    return {
        "pin": pin,
        "livekit_url": LIVEKIT_URL,
        "livekit_token": _make_token(req.room_name, f"host-{pin}", True),
        "room": req.room_name,
        "expires_in": "24 hours",
    }


@app.post("/watch")
def watch(req: WatchRequest):
    """
    Public endpoint — anyone with the 8-digit PIN can call this.
    No authentication required. Returns a subscriber-only LiveKit token.
    """
    _clean_expired()
    entry = pin_store.get(req.pin)
    if not entry:
        raise HTTPException(status_code=404, detail="Invalid or expired PIN")
    return {
        "livekit_url": LIVEKIT_URL,
        "livekit_token": _make_token(
            entry["room"],
            f"{req.viewer_name}-{int(time.time())}",
            False,
        ),
        "room": entry["room"],
    }


@app.get("/health")
def health():
    return {"status": "ok", "active_pins": len(pin_store), "ts": int(time.time())}


# Serve the web viewer from /web at the root URL
app.mount("/", StaticFiles(directory="web", html=True), name="web")
