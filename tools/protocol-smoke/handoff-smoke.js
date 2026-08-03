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
let expectedIndex = 0;
let lastBrand;
const observed = [];
const timeout = setTimeout(
  () => finish(new Error(
    `timed out after observing ${JSON.stringify(observed)}; next expected brand is `
      + JSON.stringify(options.expectedBrands[expectedIndex])
  )),
  options.timeoutMs
);

client.on("packet", (packet, metadata) => {
  if (!["custom_payload", "plugin_message"].includes(metadata.name)
      || !isBrandChannel(packet.channel)) {
    return;
  }
  const brand = decodeProtocolString(packet.data).replaceAll(/\u00c2?\u00a7./g, "");
  if (brand === lastBrand) {
    return;
  }
  lastBrand = brand;
  observed.push(brand);
  console.log(`BRAND ${JSON.stringify(brand)}`);

  if (!brand.includes(options.expectedBrands[expectedIndex])) {
    return;
  }
  expectedIndex += 1;
  if (expectedIndex === 1) {
    console.log(`READY initial-brand=${JSON.stringify(brand)}`);
  }
  if (expectedIndex === options.expectedBrands.length) {
    finish(null);
  }
});
client.on("error", error => finish(error));
client.on("end", reason => {
  if (!settled) {
    finish(new Error(`connection ended before handoff verification: ${reason}`));
  }
});

function finish(error) {
  if (settled) {
    return;
  }
  settled = true;
  clearTimeout(timeout);
  if (client.state !== "disconnected") {
    client.end("SLS-LITE handoff smoke complete");
  }
  if (error) {
    console.error(`FAIL ${options.version}: ${error.message}`);
    process.exitCode = 1;
    return;
  }
  console.log(
    `PASS ${options.version}: observed handoff sequence ${JSON.stringify(observed)}`
  );
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
    username: "SLSHANDOFF",
    version: "1.21.5",
    expectedBrands: ["Paper", "SLS-Limbo", "Paper"],
    timeoutMs: 120000
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
      case "--expected-brands":
        values.expectedBrands = value.split(",").map(item => item.trim()).filter(Boolean);
        break;
      case "--timeout-seconds":
        values.timeoutMs = positiveInteger(value, argument) * 1000;
        break;
      default:
        throw new Error(`unknown argument ${argument}`);
    }
  }
  if (values.expectedBrands.length < 2) {
    throw new Error("--expected-brands must contain at least two brands");
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
