import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../', import.meta.url));
const clean = process.argv[2] === '--clean';
const argumentOffset = clean ? 3 : 2;
const buildProfilePath = process.argv[argumentOffset] ??
  join(repositoryRoot, 'apps/harmony/build-profile.json5');
const signingDirectory = process.argv[argumentOffset + 1] ??
  join(process.env.RUNNER_TEMP ?? repositoryRoot, 'reviewfault-harmony-signing');
const originalProfilePath = join(signingDirectory, 'build-profile.original.json5');

if (clean) {
  try {
    if (existsSync(originalProfilePath)) {
      writeFileSync(buildProfilePath, readFileSync(originalProfilePath));
    }
  } finally {
    rmSync(signingDirectory, { recursive: true, force: true });
  }
  process.exit(0);
}

const required = [
  'HARMONY_KEYSTORE_BASE64',
  'HARMONY_KEYSTORE_PASSWORD',
  'HARMONY_KEY_ALIAS',
  'HARMONY_KEY_PASSWORD',
  'HARMONY_CERTIFICATE_BASE64',
  'HARMONY_PROFILE_BASE64',
];

for (const name of required) {
  assert(process.env[name]?.trim(), `Missing required secret: ${name}`);
}

mkdirSync(signingDirectory, { recursive: true, mode: 0o700 });

const material = {
  certpath: join(signingDirectory, 'reviewfault-release.cer'),
  profile: join(signingDirectory, 'reviewfault-release.p7b'),
  storeFile: join(signingDirectory, 'reviewfault-release.p12'),
};

const writeSecret = (path, encoded) => {
  writeFileSync(path, Buffer.from(encoded, 'base64'), { mode: 0o600 });
};

writeSecret(material.certpath, process.env.HARMONY_CERTIFICATE_BASE64);
writeSecret(material.profile, process.env.HARMONY_PROFILE_BASE64);
writeSecret(material.storeFile, process.env.HARMONY_KEYSTORE_BASE64);

const signingConfigs = [{
  name: 'default',
  type: 'HarmonyOS',
  material: {
    ...material,
    storePassword: process.env.HARMONY_KEYSTORE_PASSWORD,
    keyAlias: process.env.HARMONY_KEY_ALIAS,
    keyPassword: process.env.HARMONY_KEY_PASSWORD,
    signAlg: 'SHA256withECDSA',
  },
}];

const source = readFileSync(buildProfilePath, 'utf8');
const placeholder = '"signingConfigs": []';
assert(source.includes(placeholder),
  'Harmony build profile must contain an empty signingConfigs placeholder');
writeFileSync(originalProfilePath, source, { mode: 0o600 });

writeFileSync(
  buildProfilePath,
  source.replace(placeholder, `"signingConfigs": ${JSON.stringify(signingConfigs, null, 2)}`),
);
