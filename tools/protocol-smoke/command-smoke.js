"use strict";

const minecraft = require("minecraft-protocol");

const options = parseArguments(process.argv.slice(2));
const client = minecraft.createClient({
  host: options.host,
  port: options.port,
  username: options.username,
  version: options.version,
  auth: "offline",
  hideErrors: true,
  connectTimeout: options.timeoutMs
});

let settled = false;
const matched = new Set();
const timeout = setTimeout(
  () => finish(new Error(
    `timed out waiting for ${options.expected.map(JSON.stringify).join(", ")}`
  )),
  options.timeoutMs
);

client.on("login", () => {
  let delay = 500;
  for (const command of options.commands) {
    setTimeout(() => client.write("chat", {message: `/${command}`}), delay);
    delay += 500;
  }
});
client.on("packet", (packet, metadata) => {
  if (!["chat", "system_chat", "profileless_chat"].includes(metadata.name)) {
    return;
  }
  const text = JSON.stringify(packet);
  for (const expected of options.expected) {
    if (text.includes(expected)) {
      matched.add(expected);
    }
  }
  if (matched.size === options.expected.length) {
    finish(null);
  }
});
client.on("error", error => finish(error));
client.on("end", reason => {
  if (!settled) {
    finish(new Error(`connection ended before verification: ${reason}`));
  }
});

function finish(error) {
  if (settled) {
    return;
  }
  settled = true;
  clearTimeout(timeout);
  if (client.state !== "disconnected") {
    client.end("SLS-LITE command smoke complete");
  }
  if (error) {
    console.error(`FAIL: ${error.message}`);
    process.exitCode = 1;
    return;
  }
  console.log(
    `PASS ${options.version}: received ${options.expected.map(JSON.stringify).join(", ")}`
  );
}

function parseArguments(arguments_) {
  const values = {
    host: "127.0.0.1",
    port: 25565,
    username: "SLSDEBUG",
    version: "1.18.2",
    commands: [],
    expected: [],
    timeoutMs: 15000
  };
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    const value = arguments_[++index];
    if (value === undefined) {
      throw new Error(`missing value for ${argument}`);
    }
    switch (argument) {
      case "--host":
        values.host = value;
        break;
      case "--port":
        values.port = positiveInteger(value, argument);
        break;
      case "--username":
        values.username = value;
        break;
      case "--version":
        values.version = value;
        break;
      case "--command":
        values.commands.push(value);
        break;
      case "--expect":
        values.expected.push(value);
        break;
      case "--timeout-ms":
        values.timeoutMs = positiveInteger(value, argument);
        break;
      default:
        throw new Error(`unknown argument ${argument}`);
    }
  }
  if (values.commands.length === 0 || values.expected.length === 0) {
    throw new Error("at least one --command and --expect are required");
  }
  return values;
}

function positiveInteger(value, argument) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${argument} must be a positive integer`);
  }
  return parsed;
}
