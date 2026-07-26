"use strict";

const minecraft = require("minecraft-protocol");

const options = parseArguments(process.argv.slice(2));

async function main() {
  let failures = 0;
  for (const version of options.versions) {
    try {
      const result = await connect(version);
      console.log(
        `PASS ${version}: reached PLAY state, brand=${JSON.stringify(result.brand)}`
      );
    } catch (error) {
      failures += 1;
      console.error(`FAIL ${version}: ${error.message}`);
    }
  }
  process.exitCode = failures === 0 ? 0 : 1;
}

function connect(version) {
  return new Promise((resolve, reject) => {
    const username = `SLSBot_${version.replaceAll(".", "_")}`.slice(0, 16);
    const client = minecraft.createClient({
      host: options.host,
      port: options.port,
      username,
      version,
      auth: "offline",
      hideErrors: true,
      connectTimeout: options.timeoutMs
    });
    let play = false;
    let brand;
    let settled = false;

    const timeout = setTimeout(
      () => finish(new Error(
        play
          ? `PLAY reached but expected brand ${JSON.stringify(options.brand)} was not received`
          : "connection timed out before PLAY state"
      )),
      options.timeoutMs
    );

    client.on("login", () => {
      play = true;
      if (!options.brand || (brand && brand.includes(options.brand))) {
        finish(null);
      }
    });
    client.on("packet", (packet, metadata) => {
      if (process.env.SLS_PROTOCOL_DEBUG === "1") {
        const payload = metadata.name === "custom_payload"
          ? ` channel=${JSON.stringify(packet.channel)}`
          : "";
        console.log(
          `DEBUG ${version}: ${metadata.state}:${metadata.name} `
            + `[${Object.keys(packet).join(",")}]${payload}`
        );
      }
      if (metadata.name !== "custom_payload"
          && metadata.name !== "plugin_message") {
        return;
      }
      if (!isBrandChannel(packet.channel)) {
        return;
      }
      brand = decodeProtocolString(packet.data).replaceAll(/§./g, "");
      if (process.env.SLS_PROTOCOL_DEBUG === "1") {
        console.log(`DEBUG ${version}: decoded brand=${JSON.stringify(brand)}`);
      }
      if (play && (!options.brand || brand.includes(options.brand))) {
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
        client.end("SLS-LITE protocol smoke complete");
      }
      if (error) {
        reject(error);
      } else if (options.brand && (!brand || !brand.includes(options.brand))) {
        reject(new Error(
          `expected brand ${JSON.stringify(options.brand)}, received ${JSON.stringify(brand)}`
        ));
      } else {
        resolve({brand: brand || "<not required>"});
      }
    }
  });
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
    versions: ["1.21.5"],
    brand: "SLS-Limbo",
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
      case "--versions":
        values.versions = value.split(",").map(item => item.trim()).filter(Boolean);
        break;
      case "--brand":
        values.brand = value === "none" ? "" : value;
        break;
      case "--timeout-seconds":
        values.timeoutMs = positiveInteger(value, argument) * 1000;
        break;
      default:
        throw new Error(`unknown argument ${argument}`);
    }
  }
  if (values.versions.length === 0) {
    throw new Error("--versions must contain at least one Minecraft version");
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
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
