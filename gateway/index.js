const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');
const { randomUUID } = require('crypto');
const rateLimit = require('express-rate-limit');
const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server, maxPayload: 50 * 1024 * 1024 });

app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.raw({ type: '*/*' }));
app.use(rateLimit({
    windowMs: 60 * 1000,
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
    message: "Too many requests from this IP, please try again in a minute"
}));

const connectedPhones = new Map();
const viewerSessions = new Map();
const analyticsStore = new Map();
const pending = new Map();

const REQUEST_TIMEOUT_MS = 30000;

function pushStats(ws, slug) {
    if (ws.readyState !== ws.OPEN) return;
    const sessions = viewerSessions.get(slug);
    const liveCount = sessions ? sessions.size : 0;
    const stats = analyticsStore.get(slug) || { total: 0, monthly: 0, daily: 0 };
    ws.send(JSON.stringify({
        type: "STATS",
        liveViewers: liveCount,
        dailyVisits: stats.daily,
        monthlyVisits: stats.monthly,
        totalVisits: stats.total
    }));
}

wss.on('connection', (ws) => {
    console.log('New WebSocket connection established');
    ws.isAlive = true;

    ws.on('message', (msg) => {
        let data;
        try {
            data = JSON.parse(msg.toString());
        } catch (e) {
            console.error("Failed to parse message:", e.message);
            return;
        }

        if (data.type === "PONG") {
            ws.isAlive = true;
            return;
        }

        if (data.type === "REGISTER") {
            const slug = data.slug.toLowerCase();
            if (connectedPhones.has(slug)) {
                ws.send(JSON.stringify({ type: "ERROR", message: "Slug already taken" }));
                ws.close(1008, "Slug already taken");
                return;
            }
            connectedPhones.set(slug, ws);
            ws.slug = slug;
            console.log("Phone registered:", slug);
            pushStats(ws, slug);
            return;
        }

        const entry = pending.get(data.id);
        if (!entry) return;

        clearTimeout(entry.timeoutHandle);
        pending.delete(data.id);

        const { res } = entry;
        res.status(data.status || 200);

        if (data.headers) {
            Object.entries(data.headers).forEach(([key, value]) => {
                const lower = key.toLowerCase();
                if (!['content-length', 'transfer-encoding', 'connection', 'content-encoding'].includes(lower)) {
                    try { res.setHeader(key, value); } catch (_) {}
                }
            });
        }

        const body = data.binary
            ? Buffer.from(data.body, 'base64')
            : data.body;
        res.send(body);
    });

    ws.on('close', () => {
        if (ws.slug) {
            connectedPhones.delete(ws.slug);
            console.log("Phone disconnected:", ws.slug);
            pending.forEach((entry, id) => {
                if (entry.slug === ws.slug) {
                    clearTimeout(entry.timeoutHandle);
                    pending.delete(id);
                    try { entry.res.status(503).send("Device disconnected"); } catch (_) {}
                }
            });
        }
    });

    ws.on('error', (err) => {
        console.error("WS error for slug", ws.slug, err.message);
        if (ws.slug) connectedPhones.delete(ws.slug);
    });
});

// Single stats push interval
const statsInterval = setInterval(() => {
    connectedPhones.forEach((ws, slug) => pushStats(ws, slug));
}, 5000);

// Single heartbeat interval
const heartbeatInterval = setInterval(() => {
    connectedPhones.forEach((ws, slug) => {
        if (!ws.isAlive) {
            console.log("Removing dead slug:", slug);
            connectedPhones.delete(slug);
            ws.terminate();
            return;
        }
        ws.isAlive = false;
        ws.send(JSON.stringify({ type: "PING" }));
    });
}, 15000);

// Clean up stale viewer sessions
setInterval(() => {
    const cutoff = Date.now() - 5 * 60 * 1000;
    viewerSessions.forEach((sessions) => {
        sessions.forEach((lastSeen, ip) => {
            if (lastSeen < cutoff) sessions.delete(ip);
        });
    });
}, 60_000);

// Reset daily/monthly counters
setInterval(() => {
    const now = new Date();
    analyticsStore.forEach((stats) => {
        const last = new Date(stats.lastReset);
        if (last.getDate() !== now.getDate()) stats.daily = 0;
        if (last.getMonth() !== now.getMonth()) stats.monthly = 0;
        stats.lastReset = Date.now();
    });
}, 60_000);

server.on('close', () => {
    clearInterval(heartbeatInterval);
    clearInterval(statsInterval);
});

app.use((req, res) => {
    if (req.url === '/favicon.ico') return res.status(204).end();

    const parts = req.path.split('/').filter(Boolean);

    if (parts.length === 1 && !req.path.endsWith('/')) {
        return res.redirect(301, req.path + '/');
    }

    if (parts.length === 0) return res.status(404).send("No slug provided");

    // Declare slug first
    const slug = parts[0].toLowerCase();
    const phone = connectedPhones.get(slug);

    if (!phone) return res.status(404).send("Site not found or phone offline");

    // Analytics — after slug is declared
    const ip = req.headers['x-forwarded-for']?.split(',')[0] || req.ip;
    const now = Date.now();

    if (!viewerSessions.has(slug)) viewerSessions.set(slug, new Map());
    const sessions = viewerSessions.get(slug);
    const isNewVisitor = !sessions.has(ip) || (now - sessions.get(ip)) > 5 * 60 * 1000;
    sessions.set(ip, now);

    if (isNewVisitor) {
        if (!analyticsStore.has(slug)) {
            analyticsStore.set(slug, { total: 0, monthly: 0, daily: 0, lastReset: now });
        }
        const stats = analyticsStore.get(slug);
        stats.total++;
        stats.monthly++;
        stats.daily++;
    }

    const strippedPath = '/' + parts.slice(1).join('/');
    const finalPath = strippedPath === '/' ? '/' : strippedPath;

    const id = randomUUID();

    const timeoutHandle = setTimeout(() => {
        if (pending.has(id)) {
            pending.delete(id);
            try { res.status(504).send("Request timed out"); } catch (_) {}
        }
    }, REQUEST_TIMEOUT_MS);

    pending.set(id, { res, timeoutHandle, slug });

    let bodyToSend = null;
    if (req.body) {
        if (Buffer.isBuffer(req.body)) {
            bodyToSend = req.body.toString('base64');
        } else if (typeof req.body === 'object') {
            bodyToSend = JSON.stringify(req.body);
        } else {
            bodyToSend = req.body;
        }
    }

    try {
        phone.send(JSON.stringify({
            id,
            method: 'HTTP_REQUEST',
            path: finalPath || '/',
            httpMethod: req.method,
            headers: req.headers,
            body: bodyToSend
        }));
    } catch (e) {
        clearTimeout(timeoutHandle);
        pending.delete(id);
        res.status(503).send("Failed to forward request to device");
    }
});

server.listen(3000, () => {
    console.log('Gateway server listening on 3000');
});