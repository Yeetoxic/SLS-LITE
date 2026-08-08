"use strict";

const minecraft = require("minecraft-protocol");

const options = parseArguments(process.argv.slice(2));
const clients = [];

async function main() {
  try {
    const target = await connect(options.target);
    await target.command(`sls join ${options.registry} ${options.blueprint}`);
    await target.waitForAnyText(["Preparing", "Queued for"]);
    await target.waitForBrandCount(2);
    console.log("PASS target: occupied the one-player managed instance");

    await delay(options.connectionDelayMs);
    const ordinary = await connect("SLS_FORCE_NORMAL");
    await ordinary.command(`sls join player ${options.target}`);
    await ordinary.waitForText("Instance is full");
    if (ordinary.brands.length !== 1) {
      throw new Error("ordinary player changed backend despite the public capacity limit");
    }
    ordinary.end();
    console.log("PASS ordinary: public capacity could not be bypassed");

    await delay(options.connectionDelayMs);
    const unauthorized = await connect("SLS_FORCE_GUEST");
    await unauthorized.command(`sls join player ${options.target} --force`);
    await unauthorized.waitForText("do not have permission to force a player join");
    if (unauthorized.brands.length !== 1) {
      throw new Error("unauthorized player changed backend during force denial");
    }
    unauthorized.end();
    console.log("PASS permission: unauthorized force join was rejected");

    await delay(options.connectionDelayMs);
    const administrator = await connect(options.administrator);
    await administrator.command(`sls join player ${options.target} --force`);
    await administrator.waitForText("Force joining");
    await administrator.waitForText(options.administrator);
    await administrator.waitForText(options.target);
    await administrator.waitForBrandCount(2);
    console.log("PASS force: administrator reached the target's full managed instance");

    if (target.ended || target.failure) {
      throw new Error("target player was moved or disconnected by the force join");
    }
    console.log("PASS target identity: force join moved the administrator, not the target");
  } finally {
    for (const client of clients) {
      client.end();
    }
  }
}

async function connect(username) {
  const client = minecraft.createClient({
    host: options.host,
    port: options.port,
    username,
    version: options.version,
    auth: "offline",
    hideErrors: true,
    connectTimeout: options.timeoutMs
  });
  const state = {
    client,
    username,
    brands: [],
    messages: [],
    ended: false,
    failure: null,
    intentionalEnd: false,
    end() {
      if (!this.ended && client.state !== "disconnected") {
        this.intentionalEnd = true;
        client.end("SLS-LITE force-join smoke complete");
      }
    },
    async command(command) {
      ensureLive(this);
      if (client.protocolVersion <= 758) {
        client.write("chat", { message: `/${command}` });
      } else {
        client.chat(`/${command}`);
      }
      await delay(250);
    },
    waitForText(text) {
      return waitUntil(
        () => this.messages.some(message => message.toLowerCase().includes(text.toLowerCase())),
        this,
        `chat containing ${JSON.stringify(text)}`
      );
    },
    waitForAnyText(texts) {
      return waitUntil(
        () => texts.some(text => this.messages.some(
          message => message.toLowerCase().includes(text.toLowerCase())
        )),
        this,
        `chat containing one of ${JSON.stringify(texts)}`
      );
    },
    waitForBrandCount(count) {
      return waitUntil(() => this.brands.length >= count, this, `${count} backend brand packet(s)`);
    }
  };
  clients.push(state);

  client.on("packet", (packet, metadata) => {
    if (["custom_payload", "plugin_message"].includes(metadata.name)
        && isBrandChannel(packet.channel)) {
      state.brands.push(decodeProtocolString(packet.data));
    }
    if (["chat", "system_chat", "profileless_chat"].includes(metadata.name)) {
      const message = JSON.stringify(packet);
      state.messages.push(message);
      if (process.env.SLS_PROTOCOL_DEBUG === "1") {
        console.log(`${username} CHAT ${message}`);
      }
    }
  });
  client.on("error", error => {
    state.failure = error;
  });
  client.on("end", reason => {
    state.ended = true;
    if (!state.intentionalEnd && !state.failure) {
      state.failure = new Error(`connection ended: ${reason}`);
    }
  });

  await state.waitForBrandCount(1);
  await waitUntil(() => typeof client.chat === "function", state, "client command readiness");
  return state;
}

async function waitUntil(predicate, state, description) {
  const deadline = Date.now() + options.timeoutMs;
  while (!predicate()) {
    ensureLive(state);
    if (Date.now() >= deadline) {
      throw new Error(
        `${state.username} timed out waiting for ${description}; brands=`
          + `${JSON.stringify(state.brands)}, messages=${JSON.stringify(state.messages.slice(-4))}`
      );
    }
    await delay(250);
  }
}

function ensureLive(state) {
  if (state.failure) {
    throw state.failure;
  }
  if (state.ended) {
    throw new Error(`${state.username} disconnected unexpectedly`);
  }
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function isBrandChannel(channel) {
  return channel === "minecraft:brand" || channel === "MC|Brand";
}

function decodeProtocolString(value) {
  const buffer = Buffer.isBuffer(value) ? value : Buffer.from(value);
  let length = 0;
  let shift = 0;
  let offset = 0;
  while (offset < buffer.length) {
    const byte = buffer[offset++];
    length |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) {
      return buffer.subarray(offset, offset + length).toString("utf8");
    }
    shift += 7;
    if (shift > 28) {
      throw new Error("invalid brand payload length");
    }
  }
  throw new Error("truncated brand payload");
}

function parseArguments(arguments_) {
  const values = {
    host: "127.0.0.1",
    port: 25565,
    version: "1.21.11",
    registry: "test",
    blueprint: "smoke",
    target: "SLS_FORCE_TARGET",
    administrator: "SLS_FORCE_ADMIN",
    timeoutMs: 180000,
    connectionDelayMs: 4000
  };
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    const value = arguments_[++index];
    if (value === undefined) {
      throw new Error(`missing value for ${argument}`);
    }
    switch (argument) {
      case "--host": values.host = value; break;
      case "--port": values.port = positiveInteger(value, argument); break;
      case "--version": values.version = value; break;
      case "--registry": values.registry = value; break;
      case "--blueprint": values.blueprint = value; break;
      case "--target": values.target = value; break;
      case "--administrator": values.administrator = value; break;
      case "--timeout-seconds": values.timeoutMs = positiveInteger(value, argument) * 1000; break;
      case "--connection-delay-ms": values.connectionDelayMs = positiveInteger(value, argument); break;
      default: throw new Error(`unknown argument ${argument}`);
    }
  }
  return values;
}

function positiveInteger(value, argument) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${argument} must be a positive integer`);
  }
  return parsed;
}

main().catch(error => {
  console.error(`FAIL: ${error.stack || error.message}`);
  process.exitCode = 1;
});
