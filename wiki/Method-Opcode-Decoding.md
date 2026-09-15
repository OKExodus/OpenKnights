# Method: Opcode Decoding

**Definition.** How the layout of a network message was recovered from observed traffic, so the server can regenerate the exact bytes rather than replay a recording.

Read [[Method Capture]] first. This page assumes you have traffic to work from.

## The Framing

Every message is a length-prefixed frame: a small header giving the payload size, an opcode number, and the payload. Decoding a message means learning the structure of that payload: which bytes are which fields, in what types, in what order.

## Recovering a Payload Layout

1. **Fix everything but one input.** Perform the action many times, changing a single value in the game each time, and watch which bytes in the payload move. A field is found where the bytes track the value.
2. **Identify types by behavior.** A field that counts identifies as an integer of some width; a field that carries text identifies by its length prefix and character range; a field that toggles identifies as a flag. Widths are confirmed by pushing values to their limits and watching where they wrap.
3. **Order and alignment.** The order of fields is read directly from where they sit in the payload. Padding and alignment are inferred from gaps that never change.
4. **Cross-check against state.** A field's meaning is confirmed by tying it to the state change the message causes. A value in the payload that equals the id of an item that was consumed is that item's id.

## Requests, Replies, and Cascades

One client request usually produces several server messages. Decoding a request is not finished until its full set of replies is accounted for, in order. Many replies are shared across systems: an item-quantity update looks the same whether the item changed because of a purchase or a reward. Those shared replies are documented once and linked from every opcode that emits them.

## Refusals

A large part of a message's behavior is when the server refuses it. Refusals are decoded the same way: drive the action into every invalid state and record the exact refusal the server returns. A faithful reproduction has to refuse in the same cases with the same message, not only succeed in the same cases.

## Proving a Layout

A decoded layout is a hypothesis until the reconstruction regenerates the same bytes for the same inputs and the difference against the reference is zero. That is the job of [[Method Differential Harness]]. An opcode page records both the layout and the bundle that pins it.

## Where Decoded Opcodes Are Recorded

Each opcode has a page under the [[Opcode Index]], linked to the system that uses it and to the route handler that reproduces it in `server-core`.
