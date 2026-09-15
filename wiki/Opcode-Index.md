# Opcode Index

**Definition.** The catalogue of network messages exchanged between the Pocket Knights client and the server, with each message linked to the system that uses it. This is the page to keep open while reading system pages.

For how the layout of a message is recovered from observed traffic, see [[Method Opcode Decoding]]. For how a message's exact bytes are pinned against the original, see [[Method Differential Harness]].

## The Wire, in Brief

The client speaks to the server over three loopback services. Each carries length-prefixed frames. A frame is an opcode followed by a payload.

| Service | Port | Carries |
| --- | --- | --- |
| Login | 17777 | The login handshake and session heartbeat |
| Game | 19121 | All in-world gameplay messages |
| Sign-in | 17778 | The local HTTP sign-in and the free top-up |

Direction is written as a letter and a number:

- **C** is a message from the client to the server, for example `C129`.
- **S** is a message from the server to the client, for example `S4`.

A single client request often produces several server messages in reply. Those replies are listed on each opcode page in the order the server sends them.

## How This Index Is Maintained

The server reproduces every opcode in named route handlers. The numbering below is drawn from those handlers. This page lists the opcodes that have dedicated documentation or that anchor a system; it grows as system pages are written. The complete numeric set lives in the route code referenced by each system page.

## Login and Session

| Opcode | Name | System |
| --- | --- | --- |
| C3 | Authenticate a session with a token | [[Accounts and Sign-in]] |
| S18 | Character list and the create prompt | [[Character Selection and Deletion]] |
| C289 | Create: submit the character name | [[Character Creation]] |
| C291 | Create: choose the starter and commit | [[Character Creation]] |

## Heroes and Teams

| Opcode | Name | System |
| --- | --- | --- |
| S32 | Hero added to the roster | [[Heroes]] |
| S34 | Hero removed | [[Heroes]] |
| S46 | Hero state update | [[Heroes]] |
| S56 | Fortify result | [[Hero Fortify]] |
| S58 | Evolve result | [[Hero Evolution]] |
| S54 | Reborn result | [[Reborn]] |
| S38 | Hero added to the bench | [[Formation]] |
| S40 | Hero removed from the bench | [[Formation]] |
| S42 | Lineup set | [[Formation]] |
| S44 | Captain set | [[Formation]] |

## Items and Economy

| Opcode | Name | System |
| --- | --- | --- |
| S64 | Item added | [[Acquisition and Items]] |
| S66 | Item removed | [[Acquisition and Items]] |
| S68 | Item quantity update | [[Acquisition and Items]] |

## Combat

| Opcode | Name | System |
| --- | --- | --- |
| C129 | Start a campaign battle | [[Campaign]] |
| C131 | Auto-battle a campaign stage | [[Campaign]] |
| C133 | First-kill a stage | [[Campaign]] |
| S4 | Battle report | [[Battle Report]] |
| S12 | Unit position update within a battle | [[Battle Report]] |

## Reading an Opcode Page

Each opcode page states its direction and number, the layout of its request, the replies it produces, the preconditions that make the server refuse it, the data and state it touches, the route handler that reproduces it, and how its bytes were deciphered and pinned. See the [[Page Template]] for the exact shape.
