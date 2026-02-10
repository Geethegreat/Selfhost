const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });
//have to make this an array for multiple phones, but for now just one
let phonesocket = null;
const pending = new Map(); // id -> res
//create a websocket server to listen for phone connections
wss.on('connection', (ws) => {
    console.log('New WebSocket connection established');
    phonesocket = ws;

    ws.on('message', (msg) => {
        const response = JSON.parse(msg.toString());
        const res = pending.get(response.id);

        if (!res) return;

        pending.delete(response.id);
        res.send(response.body);
    });

    ws.on('close', () => {
        console.log('WebSocket connection closed');
        phonesocket = null;
    });
});

app.use((req, res) => {
    if (req.url === '/favicon.ico') {
        return res.status(204).end();
    }

    if (!phonesocket) {
        return res.status(503).send('No phone connected');
    }
//send request from client to the phone via websocket, and store the response object in a map with a unique id so we can send the response back to the correct client when we get the response from the phone
    const id = Date.now().toString();
    pending.set(id, res);

    phonesocket.send(JSON.stringify({
        id,
        method: 'HTTP_REQUEST',
        path: req.url,
        httpMethod: req.method
    }));
});

server.listen(3000, () => {
    console.log('Gateway server listening on 3000');
});
