import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";
import { deflateSync } from "node:zlib";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "..");
const fixtureDirectory = join(repositoryRoot, "backend/src/test/resources/media");
const displayP3Profile = Buffer.from(
  "AAAB4GxjbXMEIAAAbW50clJHQiBYWVogB+IAAwAUAAkADgAdYWNzcE1TRlQAAAAAc2F3c2N0cmwAAAAAAAAAAAAAAAAAAPbWAAEAAAAA0y1oYW5kwzc6zlf4VsuhS9h6V6sQYQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKZGVzYwAAAPwAAAAiY3BydAAAASAAAAAid3RwdAAAAUQAAAAUY2hhZAAAAVgAAAAsclhZWgAAAYQAAAAUZ1hZWgAAAZgAAAAUYlhZWgAAAawAAAAUclRSQwAAAcAAAAAgZ1RSQwAAAcAAAAAgYlRSQwAAAcAAAAAgbWx1YwAAAAAAAAABAAAADGVuVVMAAAAGAAAAHABzAFAAMwAAbWx1YwAAAAAAAAABAAAADGVuVVMAAAAGAAAAHABDAEMAMAAAWFlaIAAAAAAAAPbWAAEAAAAA0y1zZjMyAAAAAAABDEIAAAXe///zJQAAB5MAAP2Q///7of///aIAAAPcAADAblhZWiAAAAAAAACD3wAAPb////+7WFlaIAAAAAAAAEq/AACxNwAACrlYWVogAAAAAAAAKDgAABEKAADIuXBhcmEAAAAAAAMAAAACZmkAAPKnAAANWQAAE9AAAApb",
  "base64",
);

const width = 64;
const height = 48;
const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const heicStillBrands = new Set(["heic", "heix", "heim", "heis"]);

function crc32(buffer) {
  let value = 0xffffffff;
  for (const byte of buffer) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
    }
  }
  return (value ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const typeBytes = Buffer.from(type, "ascii");
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])));
  return Buffer.concat([length, typeBytes, data, checksum]);
}

function exifWithOrientationAndGps() {
  const make = Buffer.from("Synthetic Camera\0", "ascii");
  const rootEntryCount = 3;
  const rootIfdSize = 2 + rootEntryCount * 12 + 4;
  const makeOffset = 8 + rootIfdSize;
  const gpsOffset = makeOffset + make.length;
  const gpsIfd = Buffer.alloc(2 + 12 + 4);
  gpsIfd.writeUInt16LE(1, 0);
  gpsIfd.writeUInt16LE(0x0001, 2);
  gpsIfd.writeUInt16LE(2, 4);
  gpsIfd.writeUInt32LE(2, 6);
  gpsIfd.write("N\0", 10, "ascii");

  const tiff = Buffer.alloc(gpsOffset + gpsIfd.length);
  tiff.write("II", 0, "ascii");
  tiff.writeUInt16LE(42, 2);
  tiff.writeUInt32LE(8, 4);
  tiff.writeUInt16LE(rootEntryCount, 8);

  let offset = 10;
  tiff.writeUInt16LE(0x0112, offset);
  tiff.writeUInt16LE(3, offset + 2);
  tiff.writeUInt32LE(1, offset + 4);
  tiff.writeUInt16LE(6, offset + 8);

  offset += 12;
  tiff.writeUInt16LE(0x010f, offset);
  tiff.writeUInt16LE(2, offset + 2);
  tiff.writeUInt32LE(make.length, offset + 4);
  tiff.writeUInt32LE(makeOffset, offset + 8);

  offset += 12;
  tiff.writeUInt16LE(0x8825, offset);
  tiff.writeUInt16LE(4, offset + 2);
  tiff.writeUInt32LE(1, offset + 4);
  tiff.writeUInt32LE(gpsOffset, offset + 8);

  make.copy(tiff, makeOffset);
  gpsIfd.copy(tiff, gpsOffset);
  return tiff;
}

function createPng({ inverted = false } = {}) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;

  const rows = [];
  for (let y = 0; y < height; y += 1) {
    const row = Buffer.alloc(1 + width * 3);
    for (let x = 0; x < width; x += 1) {
      const left = x < width / 2;
      const top = y < height / 2;
      const red = left === inverted ? 30 : 235;
      const green = top === inverted ? 40 : 220;
      const blue = (left && top) === inverted ? 50 : 210;
      const pixel = 1 + x * 3;
      row[pixel] = red;
      row[pixel + 1] = green;
      row[pixel + 2] = blue;
    }
    rows.push(row);
  }

  const iccp = Buffer.concat([
    Buffer.from("DisplayP3\0\0", "ascii"),
    deflateSync(displayP3Profile),
  ]);
  const xmp = Buffer.concat([
    Buffer.from("XML:com.adobe.xmp\0\0\0\0\0", "binary"),
    Buffer.from(
      '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="37.0000" exif:GPSLongitude="127.0000" exif:BodySerialNumber="SYNTHETIC-ONLY"/></rdf:RDF></x:xmpmeta>',
      "utf8",
    ),
  ]);

  return Buffer.concat([
    pngSignature,
    chunk("IHDR", ihdr),
    chunk("iCCP", iccp),
    chunk("eXIf", exifWithOrientationAndGps()),
    chunk("iTXt", xmp),
    chunk("IDAT", deflateSync(Buffer.concat(rows), { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

function makeGenericHeif(source, target) {
  const bytes = readFileSync(source);
  const ftypOffset = bytes.indexOf(Buffer.from("ftyp", "ascii"));
  assert(ftypOffset >= 4, "HEIC fixture에 ftyp box가 필요합니다.");
  const boxOffset = ftypOffset - 4;
  const boxSize = bytes.readUInt32BE(boxOffset);
  const boxEnd = boxOffset + boxSize;
  assert(boxSize >= 16 && boxEnd <= bytes.length, "유효한 ftyp box가 필요합니다.");
  bytes.write("mif1", ftypOffset + 4, 4, "ascii");
  for (let brandOffset = ftypOffset + 12; brandOffset + 4 <= boxEnd; brandOffset += 4) {
    const brand = bytes.toString("ascii", brandOffset, brandOffset + 4);
    if (heicStillBrands.has(brand)) {
      bytes.write("mif1", brandOffset, 4, "ascii");
    }
  }
  writeFileSync(target, bytes);
}

function makeSequenceBrandedHeif(source, target) {
  const bytes = readFileSync(source);
  const ftypOffset = bytes.indexOf(Buffer.from("ftyp", "ascii"));
  assert(ftypOffset >= 4, "multi-image fixture에 ftyp box가 필요합니다.");
  bytes.write("msf1", ftypOffset + 4, 4, "ascii");
  writeFileSync(target, bytes);
}

function encode(arguments_) {
  execFileSync("heif-enc", arguments_, { stdio: "inherit" });
}

mkdirSync(fixtureDirectory, { recursive: true });
const encoderVersion = execFileSync("heif-enc", ["--version"], { encoding: "utf8" });
assert.match(encoderVersion, /1\.23\.0/);
assert.equal(displayP3Profile.length, 480);

const source = join(fixtureDirectory, "synthetic-source.png");
const secondSource = join(fixtureDirectory, "synthetic-source-2.png");
const heic = join(fixtureDirectory, "synthetic-orientation-metadata.heic");
writeFileSync(source, createPng());
writeFileSync(secondSource, createPng({ inverted: true }));

encode([
  "--hevc",
  "-q",
  "92",
  "--color-profile",
  "auto",
  "--enable-two-colr-boxes",
  "--rotate-cw",
  "90",
  "-o",
  heic,
  source,
]);
makeGenericHeif(heic, join(fixtureDirectory, "synthetic-orientation-metadata.heif"));
const multipleImages = join(fixtureDirectory, "synthetic-multiple-images.heic");
encode([
  "--hevc",
  "-q",
  "92",
  "-o",
  multipleImages,
  source,
  secondSource,
]);
makeSequenceBrandedHeif(
  multipleImages,
  join(fixtureDirectory, "synthetic-sequence-branded.heic"),
);

console.log(`Synthetic media fixtures generated in ${fixtureDirectory}`);
