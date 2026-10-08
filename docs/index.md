# wraith-odysseus-apk

An unofficial native Android client for a self-hosted
[Odysseus](https://github.com/odysseus-dev/odysseus) AI server. It is full native
Jetpack Compose, with no WebView wrapper. It talks to a stock Odysseus 1.0.3
over the same routes the server's own web UI uses, so the server needs no
changes. Not affiliated with the Odysseus project.

## What it does

Status: the bootstrap is done and the features below are landing one by one;
[the roadmap](ROADMAP.md) has the checklist.

- **Chats.** The session list, history with "load older", and streaming
  replies with the model's thinking shown separately. There is a model picker,
  agent, web and research modes, tool approvals, attachments, search and stop.
  A run survives the screen going off: the app resumes the stream and rebuilds
  it from the server's replay.
- **Email.** Every unread message across every enabled account, merged and
  sorted. You can read a message (as text), mark it unread, send, and reply.

## Scope, stated plainly

- One server per install, entered on the device. The APK carries no hostname.
- Login is the web UI's own: username, password, optional TOTP. Only the
  resulting session cookie is kept, encrypted with a Keystore key.
- The session lasts the server's fixed 7 days from login, then the app asks
  you to log in again.

See [distribution](android-distribution.md) for how to install it, and
[the roadmap](ROADMAP.md) for what comes next.
