// Dev-only receiver: registers, shows a pairing code, asks for approval, answers the sender's
// offer and plays the stream. Uses textContent only; no remote data is ever inserted as HTML.
"use strict";

const $ = (id) => document.getElementById(id);
const statusEl = $("status"), codeEl = $("code"), panel = $("panel"), video = $("video");
const approveBox = $("approve"), statsEl = $("stats"), hint2 = $("hint2");

let ws, pc, pendingCandidates = [], remoteSet = false, statsTimer, lastBytes = 0, lastTs = 0, pingTimer;

const setStatus = (t) => { statusEl.textContent = t; };
const send = (m) => ws && ws.readyState === WebSocket.OPEN && ws.send(JSON.stringify(m));

function connect() {
  const scheme = location.protocol === "https:" ? "wss://" : "ws://";
  ws = new WebSocket(scheme + location.host + "/ws");
  ws.onopen = () => {
    setStatus("registering…");
    send({ type: "register-receiver", deviceName: "Browser test page", protocolVersion: 1 });
    pingTimer = setInterval(() => send({ type: "ping" }), 20000);
  };
  ws.onmessage = (ev) => handle(JSON.parse(ev.data));
  ws.onclose = () => { clearInterval(pingTimer); teardown(); setStatus("disconnected from server; reload to retry"); showPanel(true); };
}

function showPanel(on) { panel.classList.toggle("hidden", !on); }

async function handle(m) {
  switch (m.type) {
    case "registered": send({ type: "create-pairing" }); break;
    case "pairing-created":
      codeEl.textContent = m.code;
      hint2.textContent = `Valid for ${m.ttlSeconds} s. Server: ${location.host}`;
      approveBox.style.display = "none";
      setStatus("waiting for a sender");
      showPanel(true);
      break;
    case "pairing-expired": send({ type: "create-pairing" }); break;
    case "pairing-request":
      $("who").textContent = m.senderName;
      approveBox.style.display = "block";
      $("yes").onclick = () => { approveBox.style.display = "none"; send({ type: "pairing-decision", pairingId: m.pairingId, approve: true }); };
      $("no").onclick = () => { approveBox.style.display = "none"; send({ type: "pairing-decision", pairingId: m.pairingId, approve: false }); send({ type: "create-pairing" }); };
      setStatus("approval needed");
      break;
    case "pairing-denied": setStatus("pairing " + m.reason); send({ type: "create-pairing" }); break;
    case "paired": startPeer(m.iceServers, m.peerName); break;
    case "offer": await onOffer(m.sdp); break;
    case "ice-candidate": await onRemoteCandidate(m); break;
    case "bye": setStatus("sender left (" + m.reason + ")"); teardown(); send({ type: "create-pairing" }); break;
    case "error": setStatus("error: " + m.code + " " + m.message); break;
  }
}

function startPeer(iceServers, peerName) {
  teardown();
  setStatus("paired with " + peerName + ", negotiating…");
  pc = new RTCPeerConnection({ iceServers });
  pc.onicecandidate = (e) => {
    if (e.candidate) send({ type: "ice-candidate", candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex });
  };
  pc.ontrack = (e) => {
    video.srcObject = e.streams[0] || new MediaStream([e.track]);
    try { e.receiver.jitterBufferTarget = 0; } catch (_) { /* not supported everywhere */ }
    showPanel(false);
    statsEl.hidden = false;
  };
  pc.onconnectionstatechange = () => {
    setStatus("connection: " + pc.connectionState);
    if (pc.connectionState === "connected") startStats();
  };
}

async function onOffer(sdp) {
  if (!pc) return;
  await pc.setRemoteDescription({ type: "offer", sdp });
  remoteSet = true;
  for (const c of pendingCandidates) await pc.addIceCandidate(c).catch(console.warn);
  pendingCandidates = [];
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  send({ type: "answer", sdp: answer.sdp });
}

async function onRemoteCandidate(m) {
  if (!pc || m.candidate === "") return; // "" = end of candidates
  const init = { candidate: m.candidate, sdpMid: m.sdpMid, sdpMLineIndex: m.sdpMLineIndex };
  if (!remoteSet) pendingCandidates.push(init);
  else await pc.addIceCandidate(init).catch(console.warn);
}

function startStats() {
  clearInterval(statsTimer);
  lastBytes = 0; lastTs = 0;
  statsTimer = setInterval(async () => {
    if (!pc) return;
    const report = await pc.getStats();
    let line = "";
    report.forEach((s) => {
      if (s.type === "inbound-rtp" && s.kind === "video") {
        const codec = s.codecId && report.get(s.codecId) ? report.get(s.codecId).mimeType : "?";
        const mbps = lastTs ? ((s.bytesReceived - lastBytes) * 8 / (s.timestamp - lastTs) / 1000).toFixed(1) : "…";
        lastBytes = s.bytesReceived; lastTs = s.timestamp;
        const jb = s.jitterBufferEmittedCount ? (s.jitterBufferDelay / s.jitterBufferEmittedCount * 1000).toFixed(0) : "?";
        line = `${codec}  ${s.frameWidth || "?"}x${s.frameHeight || "?"}  ${Math.round(s.framesPerSecond || 0)} fps  ${mbps} Mbps  jitter-buffer ${jb} ms  lost ${s.packetsLost}`;
      }
    });
    statsEl.textContent = line;
  }, 1000);
}

function teardown() {
  clearInterval(statsTimer);
  if (pc) { pc.close(); pc = null; }
  pendingCandidates = []; remoteSet = false;
  video.srcObject = null;
  statsEl.hidden = true;
  showPanel(true);
}

// Fullscreen the whole viewer (not just the <video>) so the stats overlay stays visible.
$("fs").onclick = () => { const v = $("viewer"); if (v.requestFullscreen) v.requestFullscreen(); };
video.ondblclick = $("fs").onclick;
$("newcode").onclick = () => { teardown(); send({ type: "bye" }); send({ type: "create-pairing" }); };
connect();
