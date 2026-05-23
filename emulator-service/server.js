const http = require('http');
const fs = require('fs');
const pathModule = require('path');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 8080);
const PROTOCOL_VERSION = 2;

const TYPE = {
    PAIR_REQUEST: 'PAIR_REQUEST',
    PAIR_RESPONSE: 'PAIR_RESPONSE',
    SESSION_START: 'SESSION_START',
    SESSION_ACK: 'SESSION_ACK',
    RANGING_START: 'RANGING_START',
    RANGING_STOP: 'RANGING_STOP',
    RANGING_SAMPLE: 'RANGING_SAMPLE',
    HEARTBEAT: 'HEARTBEAT',
    MOVE_TO: 'MOVE_TO',
    COVERAGE_UPDATE: 'COVERAGE_UPDATE',
    ERROR: 'ERROR'
};

const ERR = {
    BUSY: 1006
};

const UI_INDEX_FILE = pathModule.join(__dirname, 'ui', 'index.html');

const state = {
    connected: false,
    sessionId: 'rest-session-boot',
    connectedAtMs: 0,
    nextMessageId: 1,
    messages: [],
    rangingTimer: null,
    scenario: {
        type: 'NORMAL',
        untilMs: 0,
        driftRateMmPerSec: 80
    },
    position: { xMm: 0, yMm: 0 },
    path: [
        { xMm: 0, yMm: 0 },
        { xMm: 4000, yMm: 0 },
        { xMm: 4000, yMm: 3000 },
        { xMm: 0, yMm: 3000 }
    ],
    pathIndex: 0,
    sequence: 0,
    leftoverMm: 0,
    sampleRateHz: 5,
    speedMmPerSec: 200
    ,
    tags: [],
    nextTagId: 1
};

function nowMs() {
    return Date.now();
}

function monotonicMs() {
    return state.connectedAtMs > 0 ? Math.max(0, nowMs() - state.connectedAtMs) : 0;
}

function readJson(req) {
    return new Promise((resolve, reject) => {
        let body = '';
        req.on('data', (chunk) => {
            body += chunk.toString('utf8');
            if (body.length > 1024 * 1024) {
                reject(new Error('Body too large'));
                req.destroy();
            }
        });
        req.on('end', () => {
            if (!body.trim()) {
                resolve({});
                return;
            }
            try {
                resolve(JSON.parse(body));
            } catch (error) {
                reject(error);
            }
        });
        req.on('error', reject);
    });
}

function sendJson(res, status, payload) {
    const text = JSON.stringify(payload);
    res.writeHead(status, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(text)
    });
    res.end(text);
}

function sendHtml(res, status, html) {
    res.writeHead(status, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Length': Buffer.byteLength(html)
    });
    res.end(html);
}

function serveUi(res) {
    try {
        const html = fs.readFileSync(UI_INDEX_FILE, 'utf8');
        sendHtml(res, 200, html);
    } catch (error) {
        sendJson(res, 500, {
            error: 'UI load failed',
            detail: String(error && error.message ? error.message : error)
        });
    }
}

function normalizeTagInput(body) {
    const name = String(body.name || '').trim();
    const xMm = Number(body.xMm);
    const yMm = Number(body.yMm);

    if (!name) {
        throw new Error('Tag name is required');
    }
    if (!Number.isFinite(xMm) || !Number.isFinite(yMm)) {
        throw new Error('xMm and yMm must be numbers');
    }

    return {
        name,
        xMm: Math.round(xMm),
        yMm: Math.round(yMm)
    };
}

function enqueueMessage(messageType, payload, sessionId = state.sessionId) {
    const envelope = {
        protocolVersion: PROTOCOL_VERSION,
        messageType,
        messageId: state.nextMessageId++,
        sessionId,
        timestampMs: monotonicMs(),
        payload
    };
    state.messages.push(envelope);
    return envelope;
}

function currentScenarioType() {
    if (state.scenario.type === 'NORMAL') {
        return 'NORMAL';
    }
    if (state.scenario.untilMs > 0 && nowMs() > state.scenario.untilMs) {
        state.scenario = { type: 'NORMAL', untilMs: 0, driftRateMmPerSec: 80 };
        return 'NORMAL';
    }
    return state.scenario.type;
}

function currentDriftMmPerSec() {
    return state.scenario.type === 'DRIFT' ? state.scenario.driftRateMmPerSec : 0;
}

function stopRanging() {
    if (state.rangingTimer) {
        clearInterval(state.rangingTimer);
        state.rangingTimer = null;
    }
}

function distance(a, b) {
    const dx = b.xMm - a.xMm;
    const dy = b.yMm - a.yMm;
    return Math.hypot(dx, dy);
}

function advancePosition(distanceMm) {
    if (state.path.length < 2 || distanceMm <= 0) {
        return;
    }

    let remaining = distanceMm + state.leftoverMm;

    while (remaining > 0) {
        const from = state.path[state.pathIndex];
        const nextIndex = (state.pathIndex + 1) % state.path.length;
        const to = state.path[nextIndex];
        const segmentLength = distance(from, to);

        if (segmentLength <= 0) {
            state.pathIndex = nextIndex;
            continue;
        }

        const along = distance(from, state.position);
        const toEnd = Math.max(0, segmentLength - along);

        if (remaining >= toEnd) {
            state.position = { ...to };
            state.pathIndex = nextIndex;
            remaining -= toEnd;
            continue;
        }

        const t = (along + remaining) / segmentLength;
        state.position = {
            xMm: Math.round(from.xMm + (to.xMm - from.xMm) * t),
            yMm: Math.round(from.yMm + (to.yMm - from.yMm) * t)
        };
        remaining = 0;
    }

    state.leftoverMm = remaining;
}

function emitRangingAndCoverageTick(intervalMs) {
    const scenario = currentScenarioType();
    const frozen = scenario === 'STUCK';
    const signalLost = scenario === 'SIGNAL_LOSS';
    const interfered = scenario === 'SIGNAL_INTERFERENCE';
    const busy = scenario === 'BUSY';

    if (!frozen) {
        const mm = state.speedMmPerSec * (intervalMs / 1000);
        const previous = { ...state.position };
        advancePosition(mm);

        if (state.position.xMm !== previous.xMm || state.position.yMm !== previous.yMm) {
            enqueueMessage(TYPE.COVERAGE_UPDATE, {
                segments: [
                    {
                        fromXMm: previous.xMm,
                        fromYMm: previous.yMm,
                        toXMm: state.position.xMm,
                        toYMm: state.position.yMm
                    }
                ]
            });
        }
    }

    if (signalLost) {
        return;
    }

    if (busy) {
        return;
    }

    state.sequence += 1;
    const drift = currentDriftMmPerSec() * (intervalMs / 1000);
    const distanceMm = Math.max(0, Math.round(Math.hypot(state.position.xMm + drift, state.position.yMm)));
    enqueueMessage(TYPE.RANGING_SAMPLE, {
        distanceMm,
        quality: interfered ? 0.3 : 0.95,
        rssiDbm: -62,
        sequence: state.sequence,
        anchorId: 'emu-anchor-1'
    });
}

function startRanging(sampleRateHz) {
    stopRanging();
    state.sampleRateHz = Math.max(1, Number(sampleRateHz) || 5);
    const intervalMs = Math.max(100, Math.floor(1000 / state.sampleRateHz));
    state.rangingTimer = setInterval(() => emitRangingAndCoverageTick(intervalMs), intervalMs);
}

function parseBooleanQueryValue(rawValue, defaultValue = false) {
    if (rawValue === null || rawValue === undefined) {
        return defaultValue;
    }
    const normalized = String(rawValue).trim().toLowerCase();
    if (normalized === '1' || normalized === 'true' || normalized === 'yes') {
        return true;
    }
    if (normalized === '0' || normalized === 'false' || normalized === 'no') {
        return false;
    }
    return defaultValue;
}

function parsePositiveInt(rawValue, defaultValue, minValue, maxValue) {
    const parsed = Number(rawValue);
    if (!Number.isFinite(parsed)) {
        return defaultValue;
    }
    return Math.max(minValue, Math.min(maxValue, Math.floor(parsed)));
}

function createDebugSnapshot(options = {}) {
    const includeMessages = Boolean(options.includeMessages);
    const messageLimit = parsePositiveInt(options.messageLimit, 50, 1, 2000);
    const messages = includeMessages
        ? state.messages.slice(Math.max(0, state.messages.length - messageLimit))
        : [];

    return {
        serverTimeMs: nowMs(),
        monotonicSessionTimeMs: monotonicMs(),
        connected: state.connected,
        sessionId: state.sessionId,
        connectedAtMs: state.connectedAtMs,
        protocolVersion: PROTOCOL_VERSION,
        transport: {
            rangingRunning: Boolean(state.rangingTimer),
            sampleRateHz: state.sampleRateHz,
            speedMmPerSec: state.speedMmPerSec
        },
        scenario: {
            activeType: currentScenarioType(),
            rawType: state.scenario.type,
            untilMs: state.scenario.untilMs,
            driftRateMmPerSec: state.scenario.driftRateMmPerSec
        },
        mower: {
            position: { ...state.position },
            path: state.path.map((point) => ({ ...point })),
            pathIndex: state.pathIndex,
            sequence: state.sequence,
            leftoverMm: state.leftoverMm
        },
        tags: state.tags.map((tag) => ({ ...tag })),
        queue: {
            messageCount: state.messages.length,
            nextMessageId: state.nextMessageId,
            includeMessages,
            messageLimit,
            messages
        }
    };
}

async function handleRequest(req, res) {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const path = url.pathname;

    if (req.method === 'GET' && (path === '/' || path === '/ui')) {
        serveUi(res);
        return;
    }

    if (req.method === 'GET' && path === '/health') {
        sendJson(res, 200, { ok: true });
        return;
    }

    if (req.method === 'GET' && path === '/api/v1/debug/memory') {
        const includeMessages = parseBooleanQueryValue(url.searchParams.get('includeMessages'), false);
        const messageLimit = parsePositiveInt(url.searchParams.get('messageLimit'), 50, 1, 2000);
        sendJson(res, 200, createDebugSnapshot({ includeMessages, messageLimit }));
        return;
    }

    if (req.method === 'POST' && path === '/api/v1/device/connect') {
        state.connected = true;
        state.connectedAtMs = nowMs();
        state.sessionId = `rest-session-${state.connectedAtMs}`;
        sendJson(res, 200, { sessionId: state.sessionId });
        return;
    }

    if (req.method === 'POST' && path === '/api/v1/device/disconnect') {
        state.connected = false;
        stopRanging();
        sendJson(res, 200, { ok: true });
        return;
    }

    if (req.method === 'POST' && path === '/api/v1/device/send') {
        const envelope = await readJson(req);
        const messageType = envelope.messageType;
        const payload = envelope.payload || {};
        const incomingSessionId = envelope.sessionId || state.sessionId;

        if (messageType === TYPE.PAIR_REQUEST) {
            enqueueMessage(TYPE.PAIR_RESPONSE, {
                accepted: true,
                deviceInstanceId: 'rest-emulator-1'
            }, incomingSessionId);
        } else if (messageType === TYPE.SESSION_START) {
            state.sessionId = incomingSessionId || state.sessionId;
            enqueueMessage(TYPE.SESSION_ACK, { ok: true }, state.sessionId);
        } else if (messageType === TYPE.HEARTBEAT) {
            enqueueMessage(TYPE.HEARTBEAT, { status: 'ok' }, state.sessionId);
        } else if (messageType === TYPE.RANGING_START) {
            startRanging(payload.sampleRateHz);
        } else if (messageType === TYPE.RANGING_STOP) {
            stopRanging();
        } else if (messageType === TYPE.MOVE_TO && currentScenarioType() === 'BUSY') {
            enqueueMessage(TYPE.ERROR, {
                code: ERR.BUSY,
                name: 'ERR_BUSY',
                detail: 'Mower is busy in emulator scenario',
                failedMessageId: Number(envelope.messageId) || 0
            }, state.sessionId);
        }

        sendJson(res, 200, { ok: true });
        return;
    }

    if (req.method === 'GET' && path === '/api/v1/device/messages') {
        const sinceIdRaw = url.searchParams.get('sinceId');
        const sinceId = sinceIdRaw ? Number(sinceIdRaw) : null;
        const result = Number.isFinite(sinceId)
            ? state.messages.filter((message) => message.messageId > sinceId)
            : state.messages;
        sendJson(res, 200, { messages: result });
        return;
    }

    if (req.method === 'GET' && path === '/api/v1/emulator/state') {
        sendJson(res, 200, {
            activeScenario: currentScenarioType(),
            position: { ...state.position },
            connected: state.connected,
            sessionId: state.sessionId,
            tags: [...state.tags]
        });
        return;
    }

    if (req.method === 'GET' && path === '/api/v1/emulator/tags') {
        sendJson(res, 200, { tags: [...state.tags] });
        return;
    }

    if (req.method === 'POST' && path === '/api/v1/emulator/tags') {
        const body = await readJson(req);
        const tagInput = normalizeTagInput(body);
        const tag = {
            id: String(body.id || `tag-${state.nextTagId++}`),
            ...tagInput
        };
        state.tags.push(tag);
        sendJson(res, 200, { ok: true, tag });
        return;
    }

    if (req.method === 'DELETE' && path.startsWith('/api/v1/emulator/tags/')) {
        const tagId = decodeURIComponent(path.replace('/api/v1/emulator/tags/', ''));
        const before = state.tags.length;
        state.tags = state.tags.filter((tag) => tag.id !== tagId);
        sendJson(res, 200, { ok: true, deleted: before - state.tags.length });
        return;
    }

    if (req.method === 'PUT' && path.startsWith('/api/v1/emulator/tags/')) {
        const tagId = decodeURIComponent(path.replace('/api/v1/emulator/tags/', ''));
        const body = await readJson(req);
        const xMm = Number(body.xMm);
        const yMm = Number(body.yMm);
        if (!Number.isFinite(xMm) || !Number.isFinite(yMm)) {
            sendJson(res, 400, { error: 'xMm and yMm must be numbers' });
            return;
        }

        const tag = state.tags.find((currentTag) => currentTag.id === tagId);
        if (!tag) {
            sendJson(res, 404, { error: 'Tag not found' });
            return;
        }

        tag.xMm = Math.round(xMm);
        tag.yMm = Math.round(yMm);
        sendJson(res, 200, { ok: true, tag: { ...tag } });
        return;
    }

    if (req.method === 'PUT' && path === '/api/v1/emulator/mower-position') {
        const body = await readJson(req);
        const xMm = Number(body.xMm);
        const yMm = Number(body.yMm);
        if (!Number.isFinite(xMm) || !Number.isFinite(yMm)) {
            sendJson(res, 400, { error: 'xMm and yMm must be numbers' });
            return;
        }
        state.position = {
            xMm: Math.round(xMm),
            yMm: Math.round(yMm)
        };
        sendJson(res, 200, { ok: true, position: { ...state.position } });
        return;
    }

    if (req.method === 'POST' && path === '/api/v1/emulator/scenario/activate') {
        const body = await readJson(req);
        const type = String(body.type || 'NORMAL').toUpperCase();
        const durationMs = Math.max(500, Number(body.durationMs) || 5000);
        const driftRateMmPerSec = Math.max(1, Number(body.driftRateMmPerSec) || 80);

        if (type === 'DRIFT') {
            state.scenario = { type, untilMs: 0, driftRateMmPerSec };
        } else if (type === 'NORMAL') {
            state.scenario = { type: 'NORMAL', untilMs: 0, driftRateMmPerSec: 80 };
        } else {
            state.scenario = {
                type,
                untilMs: nowMs() + durationMs,
                driftRateMmPerSec: 80
            };
        }

        sendJson(res, 200, { ok: true, activeScenario: state.scenario.type });
        return;
    }

    if (req.method === 'POST' && path === '/api/v1/emulator/scenario/clear') {
        state.scenario = { type: 'NORMAL', untilMs: 0, driftRateMmPerSec: 80 };
        sendJson(res, 200, { ok: true });
        return;
    }

    sendJson(res, 404, { error: 'Not Found' });
}

const server = http.createServer((req, res) => {
    handleRequest(req, res).catch((error) => {
        sendJson(res, 500, {
            error: 'Internal Server Error',
            detail: String(error && error.message ? error.message : error)
        });
    });
});

server.listen(PORT, () => {
    console.log(`Emulator REST service listening on http://0.0.0.0:${PORT}`);
});
