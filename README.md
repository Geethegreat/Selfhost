
### Core Components

- **Gateway Server**
  - Multi-tenant routing via slug
  - WebSocket request multiplexing
  - Request-response correlation
  - Heartbeat detection
  - Automatic cleanup of dead connections

- **Android Agent**
  - Local HTTP server (NanoHTTPD)
  - WebSocket client (OkHttp)
  - Auto-reconnect with exponential backoff
  - Slug registration
  - PING/PONG heartbeat support

---

## Key Features

- Automatic reconnect on network failure
- Heartbeat system to detect dead connections
- Multi-client support (multiple browsers per device)
- Request ID-based multiplexing over WebSocket
- Slug-based routing (`/your-slug`)
- Basic slug collision detection
- Real-time request forwarding

---

## Current Status

SelfHost is currently in **v1 (Android MVP+) stage**.

It supports:
- Static site hosting
- Multiple concurrent users
- Slug-based multi-device routing

Planned improvements:
- Request timeouts on gateway
- Authentication layer
- Slug ownership system
- Monitoring & analytics
- Custom domains
- Cross-platform agents (Windows, Linux, iOS)

---

## Android Agent Setup

1. Select your website folder.
2. Enter a unique slug.
3. Start hosting.
4. Share your public URL.

Example:

https://selfhost.com/mysite/

---

## Gateway Setup (Node.js)

1. Install dependencies:
2. Run the gateway:
3. Ensure your WebSocket URL matches the Android agent configuration.

---

## Technical Highlights

- WebSocket-based full-duplex tunnel
- Asynchronous request forwarding
- Concurrent request handling
- Path rewriting for subpath hosting
- Directory normalization (`/slug` → `/slug/` redirect)

---

## Target Users (v1)

- College students needing instant demo links
- Hackathon participants
- Developers testing static projects
- Anyone who wants quick public hosting without cloud setup

---

## Vision

SelfHost aims to become a cross-platform edge hosting SaaS platform where any device can act as a deployable node.

Future roadmap:
- Multi-platform device agents
- Secure authentication
- Device clusters
- Distributed edge routing
- Paid hosting tiers

---

## Limitations (Current Version)

- Static site hosting only
- No authentication layer yet
- No rate limiting
- Not optimized for large file streaming
- Designed for light to moderate traffic

---

## Why SelfHost?

Traditional hosting requires:
- Cloud accounts
- Server setup
- DNS configuration
- Deployment pipelines

SelfHost removes all of that.

It makes hosting instant.

---


## 🤝 Contributing

This project is actively evolving. Contributions, feedback, and ideas are welcome.

---



Distributed systems enthusiast.  
Building infrastructure from the ground up.
