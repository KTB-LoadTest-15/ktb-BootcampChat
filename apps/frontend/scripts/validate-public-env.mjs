import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const frontendRoot = path.resolve(__dirname, '..');
const manifestPath = path.join(frontendRoot, '.build-public-env.json');

const requiredVars = ['NEXT_PUBLIC_API_URL', 'NEXT_PUBLIC_SOCKET_URL'];

const values = Object.fromEntries(
  requiredVars.map((key) => [key, process.env[key]?.trim() || ''])
);

for (const [key, value] of Object.entries(values)) {
  if (!value) {
    console.error(`[frontend-env] ${key} is required.`);
    process.exit(1);
  }

  try {
    const parsed = new URL(value);
    if (!['http:', 'https:'].includes(parsed.protocol)) {
      throw new Error(`unsupported protocol: ${parsed.protocol}`);
    }
  } catch (error) {
    console.error(`[frontend-env] ${key} must be a valid http/https URL. received=${value}`);
    process.exit(1);
  }
}

const fingerprint = crypto
  .createHash('sha256')
  .update(requiredVars.map((key) => `${key}=${values[key]}`).join('\n'))
  .digest('hex')
  .slice(0, 12);

const manifest = {
  apiUrl: values.NEXT_PUBLIC_API_URL,
  socketUrl: values.NEXT_PUBLIC_SOCKET_URL,
  fingerprint,
  generatedAt: new Date().toISOString()
};

fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

console.info(
  `[frontend-env] validated api=${manifest.apiUrl} socket=${manifest.socketUrl} fingerprint=${manifest.fingerprint}`
);
