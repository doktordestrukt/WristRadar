# Groups (MK19) - design

Goal: more than two people. Pair with several watches, chat 1:1 with each, chat in groups, and
call in groups - without weakening the security model or the stability work of MK18.

## 1. Contacts (replaces "the one partner")

* `TrustStore` keeps a **list of contacts**: identity key (Ed25519), pairing PSK (Keystore-sealed),
  ML-DSA-87 key, name, paired-at. The existing partner is migrated as the **first contact**
  ("primary"). `trust.partner` stays as "the primary contact" so every existing 1:1 screen keeps
  working unchanged.
* Pairing no longer requires being unpaired: pairing a new watch **adds** a contact.
* Contact id = hex of its identity key. Unpairing removes one contact (and its chat).

## 2. Engine: one link per contact

* Per-contact state (`PeerLink`): session, connecting flag, last device seen, distance estimator,
  failure/back-off counters, advert tokens, liveness timestamps, KEM/suite, PQ flags.
* **Advertising**: one advert at a time, so the advertised token **rotates** between contacts that
  are not yet connected (each tick). Scanning matches the tokens of *all* contacts.
* **Who is this?** A HELLO does not name the client, but the server must know which PSK to use.
  MK19 clients first send `HS_WHO` = nonce(16) | HMAC-SHA256(psk, "WR-WHO" | nonce)[0..16]. The
  server finds the contact whose PSK matches. No `HS_WHO` (older app) = primary contact. Nothing
  about the identity leaks: the tag is only checkable with the PSK.
* Role rule (lower key connects first), duplicate-link handling (`SESSION_DROP`), liveness,
  re-keying and upgrades all stay exactly as in MK18, per contact.

## 3. Conversations

* `messages.conv` column (DB v6). `""` = the primary contact (all existing messages),
  `c:<contactId>` = other contacts, `g:<groupId>` = groups.
* Actions (ACK, SEEN, REACT, EDIT, DELETE, PIN) are routed to the peer(s) of the message's
  conversation.

## 4. Group chats

* A group = id (16 random bytes), name, roster (each member's identity key + name), creator.
* `GROUP` message (creator -> members over their 1:1 sessions): roster + creator's Ed25519
  signature. A member accepts it only from a contact and only if it is in the roster.
* `GTEXT`: groupId | msgId | origin key | sentAt | text | Ed25519 signature by the origin over
  `"WR-GTEXT" | groupId | msgId | sentAt | text`. Every member verifies the signature against the
  roster, so a relay cannot forge or alter messages.
* **Delivery = flooding over 1:1 sessions with de-duplication**: the sender gives the message to
  every group member it is connected to; each receiver stores it once (by msgId) and passes it on
  to the members *it* is connected to. So members that are not paired with each other still get
  every message through a member they share. Everything travels inside the normal per-pair
  encryption.

## 5. Group calls

* Call with several contacts at once; every participant sends its microphone to each other
  participant it has a session with, and mixes everything it receives.
* Audio blocks carry the speaker's id so the mixer keeps one stream per person.

## 6. Compatibility

* Older apps never see `HS_WHO`, `GROUP`, `GTEXT` or group-call frames from a contact that has
  not updated (version gating, as for other new features).
* An older primary friend keeps working exactly as before.
