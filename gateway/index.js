const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.raw({ type: '*/*' }));

//have to make this an array for multiple phones, but for now just one
const connectedPhones = new Map();
const pending = new Map(); // id -> res
//create a websocket server to listen for phone connections
wss.on('connection', (ws) => {
    console.log('New WebSocket connection established');

    ws.on('message', (msg) => {
        const data = JSON.parse(msg.toString());

        // 🔥 1️⃣ REGISTER SLUG
        if (data.type === "REGISTER") {
            const slug = data.slug.toLowerCase();

            if (connectedPhones.has(slug)) {
                ws.send(JSON.stringify({
                    type: "ERROR",
                    message: "Slug already taken"
                }));
                return;
            }

            connectedPhones.set(slug, ws);
            ws.slug = slug;

            console.log("Phone registered:", slug);
            return;
        }

        // 🔥 2️⃣ HANDLE HTTP RESPONSE FROM PHONE
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
});

app.use((req, res) => {

    if (req.url === '/favicon.ico') {
        return res.status(204).end();
    }

    const parts = req.path.split('/').filter(Boolean);

    if (parts.length === 0) {
        return res.status(404).send("No slug provided");
    }

    const slug = parts[0].toLowerCase();
    const phone = connectedPhones.get(slug);

    if (!phone) {
        return res.status(404).send("Site not found or phone offline");
    }

    // Remove slug from path
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
