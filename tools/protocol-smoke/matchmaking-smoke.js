"use strict";

const minecraft = require("minecraft-protocol");

const options = parseArguments(process.argv.slice(2));
const clients = [];

async function main() {
  try {
    const queued = await connect("SLS_QUEUE", options.version);
    await queued.command(`sls join ${options.registry} ${options.blueprint}`);
    await queued.waitForAnyText(["Preparing", "Queued for"]);
    await queued.command("sls dequeue");
    await queued.waitForText("dequeued");
    queued.end();
    // All local bots share one source address; exceed Velocity's connection
    // throttle before opening the next disposable client.
    await delay(4000);
    console.log("PASS queue: queued preparation can be cancelled explicitly");

    const first = await connect("SLS_FULL_A", options.version);
    await first.command(`sls join ${options.registry} ${options.blueprint}`);
    await first.waitForAnyText(["Preparing", "Queued for"]);
    await first.waitForBrandCount(2);
    console.log("PASS transfer A: first capacity slot reached its managed backend");

    const second = await connect("SLS_FULL_B", options.version);
    await second.command(`sls join ${options.registry} ${options.blueprint}`);
    await second.waitForAnyText(["Preparing", "Queued for"]);
    await second.waitForBrandCount(2);
    console.log("PASS transfer B: second allowed instance reached its managed backend");

    const rejected = await connect("SLS_FULL_C", options.version);
    await rejected.command("sls registries");
    await rejected.waitForText("Registries");
    await rejected.waitForText("- lobby");
    await rejected.waitForText(`- ${options.registry}`);
    console.log("PASS registries: lobby and matchmaking registries are visible");
    await rejected.command(`sls join ${options.registry} ${options.blueprint}`);
    await rejected.waitForText("are full and every");
    console.log("PASS capacity: third client was rejected at the configured pool limit");

    console.log(
      `PASS ${options.version}: queue, transfer, multiple-registry, and full-pool scenario completed`
    );
  } finally {
    for (const client of clients) {
      client.end();
    }
  }
}

async function connect(username, version) {
  const client = minecraft.createClient({
    host: options.host,
    port: options.port,
    username,
    version,
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
        client.end("SLS-LITE matchmaking smoke complete");
      }
    },
    async command(command) {
      ensureLive(this);
      client.chat(`/${command}`);
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
      return waitUntil(
        () => this.brands.length >= count,
        this,
        `${count} backend brand packet(s)`
      );
    }
  };
  clients.push(state);

  client.on("packet", (packet, metadata) => {
    if (["custom_payload", "plugin_message"].includes(metadata.name)
        && isBrandChannel(packet.channel)) {
      const brand = decodeProtocolString(packet.data);
      state.brands.push(brand);
      console.log(`${username} BRAND ${JSON.stringify(brand)}`);
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
  return state;
}

async function waitUntil(predicate, state, description) {
  const deadline = Date.now() + options.timeoutMs;
  while (!predicate()) {
    ensureLive(state);
    if (Date.now() >= deadline) {
      throw new Error(
        `${state.username} timed out waiting for ${description}; brands=`
          + `${JSON.stringify(state.brands)}, messages=${JSON.stringify(state.messages.slice(-3))}`
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
    version: "1.21.5",
    registry: "minigame",
    blueprint: "stage1_lifecycle",
    timeoutMs: 180000
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
      case "--timeout-seconds": values.timeoutMs = positiveInteger(value, argument) * 1000; break;
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
