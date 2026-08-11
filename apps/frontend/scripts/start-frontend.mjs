import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const appRoot = path.resolve(__dirname, '..', '..');
const manifestPath = path.join(appRoot, 'apps', 'frontend', '.build-public-env.json');

try {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  console.info(
    `[frontend-start] api=${manifest.apiUrl} socket=${manifest.socketUrl} fingerprint=${manifest.fingerprint}`
  );
} catch (error) {
  console.error(`[frontend-start] missing or invalid build env manifest: ${manifestPath}`);
  process.exit(1);
}

const child = spawn('node', ['apps/frontend/server.js'], {
  stdio: 'inherit',
  cwd: appRoot,
  env: process.env
});

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }

  process.exit(code ?? 1);
});
