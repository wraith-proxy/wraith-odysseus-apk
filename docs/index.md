# wraith-odysseus-apk

An unofficial native Android client for a self-hosted
[Odysseus](https://github.com/odysseus-dev/odysseus) AI server. It is full native
Jetpack Compose, with no WebView wrapper. It talks to a stock Odysseus 1.0.3
over the same routes the server's own web UI uses, so the server needs no
changes. Not affiliated with the Odysseus project.

## What it does

Status: released on F-Droid and Gitea Releases. The real-phone device
checklist is still open; [the roadmap](ROADMAP.md) has the details.

- **Chats.** The session list, history, and streaming replies with the model's
  thinking shown separately. There is a model picker, agent, web and research
  modes, tool approvals, photo and file attachments, search and stop. A run
  survives the screen going off and is rebuilt from the server's replay. Hold
  a message to copy, edit, resend, regenerate, fork, rewrite or delete it; hold
  a chat to rename, favourite, copy, move to a folder, archive or delete it.
- **Email.** Accounts, folders, filters and search. HTML mail loads images
  only on request; attachments, mark unread, send, reply, reply all, forward,
  translate, schedule send and AI reply.
- **Tools.** Native screens for Notes, Calendar, Memory, Skills, Deep
  Research, Tasks, Gallery, Library and Theme. Compare and Cookbook open the
  web UI.
- **The rest.** About (version, licences, the update-check disclosure), an
  FAQ, log out, and a dialog when a newer release exists.

## Scope, stated plainly

- One server per install, entered on the device. The APK carries no hostname.
- Login is the web UI's own: username, password, optional TOTP. Only the
  resulting session cookie is kept, encrypted with a Keystore key.
- The session lasts the server's fixed 7 days from login, then the app asks
  you to log in again. If the server ends it early, the app shows the login
  screen over whatever was open and goes back there once you log in again as
  the same account.

See [distribution](android-distribution.md) for how to install it, and
[the roadmap](ROADMAP.md) for what comes next.
