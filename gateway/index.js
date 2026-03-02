const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');
const { randomUUID } = require('crypto');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server, maxPayload: 50 * 1024 * 1024 }); // 50 MB max payload

app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.raw({ type: '*/*' }));

const connectedPhones = new Map();
const pending = new Map(); // id -> { res, timeoutHandle, slug }

const REQUEST_TIMEOUT_MS = 30000;

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
            return;
        }

        // Handle HTTP response from phone
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

            // Fail any in-flight requests for this phone
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

// Single heartbeat interval outside connection handler
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

server.on('close', () => clearInterval(heartbeatInterval));

app.use((req, res) => {
    if (req.url === '/favicon.ico') return res.status(204).end();

    const parts = req.path.split('/').filter(Boolean);

    if (parts.length === 1 && !req.path.endsWith('/')) {
        return res.redirect(301, req.path + '/');
    }

    if (parts.length === 0) return res.status(404).send("No slug provided");

    const slug = parts[0].toLowerCase();
    const phone = connectedPhones.get(slug);

    if (!phone) return res.status(404).send("Site not found or phone offline");

    const strippedPath = '/' + parts.slice(1).join('/');
    const finalPath = strippedPath === '/' ? '/' : strippedPath;

    const id = randomUUID();

    // Per-request timeout
    const timeoutHandle = setTimeout(() => {
        if (pending.has(id)) {
            pending.delete(id);
            try { res.status(504).send("Request timed out"); } catch (_) {}
        }
    }, REQUEST_TIMEOUT_MS);

    pending.set(id, { res, timeoutHandle, slug });

    // Normalize request body
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