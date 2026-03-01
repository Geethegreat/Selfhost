const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.raw({ type: '*/*' }));

const connectedPhones = new Map();
const pending = new Map();

wss.on('connection', (ws) => {
    console.log('New WebSocket connection established');
    ws.isAlive = true;

    ws.on('message', (msg) => {
        const data = JSON.parse(msg.toString());

        if (data.type === "PONG") {
            ws.isAlive = true;
            return;
        }

        if (data.type === "REGISTER") {
            const slug = data.slug.toLowerCase();

            if (connectedPhones.has(slug)) {
                ws.send(JSON.stringify({ type: "ERROR", message: "Slug already taken" }));
                ws.close(1008, "Slug already taken"); // ✅ close after error so client knows cleanly
                return;
            }

            connectedPhones.set(slug, ws);
            ws.slug = slug;
            console.log("Phone registered:", slug);
            return;
        }

        const res = pending.get(data.id);
        if (!res) return;

        pending.delete(data.id);
        res.status(data.status || 200);

        if (data.headers) {
            Object.entries(data.headers).forEach(([key, value]) => {
                const lower = key.toLowerCase();
                if (!['content-length', 'transfer-encoding', 'connection', 'content-encoding'].includes(lower)) {
                    res.setHeader(key, value);
                }
            });
        }

        res.send(data.body);
    });

    ws.on('close', () => {
        if (ws.slug) {
            connectedPhones.delete(ws.slug);
            console.log("Phone disconnected:", ws.slug);
        }
    });

    ws.on('error', (err) => {
        console.error("WS error for slug", ws.slug, err.message);
        if (ws.slug) connectedPhones.delete(ws.slug);
    });
});

// ✅ Single interval outside connection handler
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

// ✅ Clean up interval if server shuts down
server.on('close', () => clearInterval(heartbeatInterval));

// ✅ Add pending request timeout to avoid memory leaks
setInterval(() => {
    const now = Date.now();
    pending.forEach((res, id) => {
        const timestamp = parseFloat(id); // id starts with Date.now()
        if (now - timestamp > 30000) {
            pending.delete(id);
            res.status(504).send("Request timed out");
        }
    });
}, 10000);

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

    const id = Date.now().toString() + Math.random();
    pending.set(id, res);

    phone.send(JSON.stringify({
        id,
        method: 'HTTP_REQUEST',
        path: finalPath || '/',
        httpMethod: req.method,
        headers: req.headers,
        body: req.body || null
    }));
});

server.listen(3000, () => {
    console.log('Gateway server listening on 3000');
});