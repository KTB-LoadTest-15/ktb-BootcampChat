#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const axios = require('axios');
const yargs = require('yargs/yargs');
const { hideBin } = require('yargs/helpers');

const cliArgs = hideBin(process.argv);
if (cliArgs[0] === '--') cliArgs.shift();

const argv = yargs(cliArgs)
  .option('api-url', {
    description: 'Backend REST API URL',
    type: 'string',
    default: 'http://localhost:5001'
  })
  .option('room-id', {
    description: 'Existing public room ID (latest public room when omitted)',
    type: 'string'
  })
  .option('users', {
    alias: 'u',
    description: 'Number of unique virtual users',
    type: 'number',
    default: 100
  })
  .option('concurrency', {
    alias: 'c',
    description: 'Number of concurrent upload workers',
    type: 'number',
    default: 100
  })
  .option('setup-concurrency', {
    description: 'Concurrency used only while creating and logging in users',
    type: 'number',
    default: 10
  })
  .option('file', {
    description: 'Image file to upload',
    type: 'string',
    default: path.resolve(__dirname, '../e2e/fixtures/images/profile.jpg')
  })
  .option('presign-timeout', {
    description: 'Presign API timeout in milliseconds',
    type: 'number',
    default: 15000
  })
  .option('put-timeout', {
    description: 'S3 PUT timeout in milliseconds',
    type: 'number',
    default: 30000
  })
  .option('cleanup-files', {
    description: 'Delete successfully uploaded files after measurement',
    type: 'boolean',
    default: true
  })
  .strict()
  .help()
  .parse();

const config = {
  apiUrl: argv.apiUrl.replace(/\/+$/, ''),
  roomId: argv.roomId,
  users: Math.max(1, Math.floor(argv.users)),
  concurrency: Math.max(1, Math.floor(argv.concurrency)),
  setupConcurrency: Math.max(1, Math.floor(argv.setupConcurrency)),
  filePath: path.resolve(argv.file),
  presignTimeout: Math.max(1, Math.floor(argv.presignTimeout)),
  putTimeout: Math.max(1, Math.floor(argv.putTimeout)),
  cleanupFiles: argv.cleanupFiles
};

const password = 'LoadTest1234!';
const runId = `${Date.now()}-${process.pid}`;
const image = fs.readFileSync(config.filePath);
const originalname = path.basename(config.filePath);
const mimetype = originalname.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';

function authHeaders(user) {
  return {
    Authorization: `Bearer ${user.token}`,
    'x-session-id': user.sessionId
  };
}

function elapsedMs(startedAt) {
  return Number(process.hrtime.bigint() - startedAt) / 1e6;
}

function percentile(sorted, ratio) {
  if (sorted.length === 0) return 0;
  return sorted[Math.max(0, Math.ceil(sorted.length * ratio) - 1)];
}

function summarize(label, values, statuses, errors, total, durationSeconds) {
  const sorted = [...values].sort((a, b) => a - b);
  const average = sorted.length
    ? sorted.reduce((sum, value) => sum + value, 0) / sorted.length
    : 0;
  const successes = [...statuses.entries()]
    .filter(([status]) => status >= 200 && status < 300)
    .reduce((sum, [, count]) => sum + count, 0);

  console.log(`\n${label}`);
  console.log(`Success      : ${successes}/${total} (${(successes / total * 100).toFixed(1)}%)`);
  console.log(`Status       : ${[...statuses.entries()].sort().map(([status, count]) => `${status}=${count}`).join(', ') || '-'}`);
  console.log(`Errors       : ${errors.length}${errors.length ? ` (${errors.slice(0, 5).join(', ')})` : ''}`);
  console.log(`Throughput   : ${(total / durationSeconds).toFixed(2)} req/s`);
  console.log(`Latency avg  : ${average.toFixed(1)} ms`);
  console.log(`Latency p50  : ${percentile(sorted, 0.50).toFixed(1)} ms`);
  console.log(`Latency p90  : ${percentile(sorted, 0.90).toFixed(1)} ms`);
  console.log(`Latency p95  : ${percentile(sorted, 0.95).toFixed(1)} ms`);
  console.log(`Latency p99  : ${percentile(sorted, 0.99).toFixed(1)} ms`);
}

async function runWorkers(items, concurrency, task) {
  let nextIndex = 0;
  async function worker() {
    while (true) {
      const index = nextIndex++;
      if (index >= items.length) return;
      await task(items[index], index);
    }
  }
  await Promise.all(Array.from(
    { length: Math.min(concurrency, items.length) },
    () => worker()
  ));
}

async function prepareUsers() {
  const users = Array.from({ length: config.users }, (_, index) => ({
    email: `file-upload-${runId}-${index}@loadtest.local`,
    password,
    name: `File Upload VU ${index}`
  }));

  process.stdout.write(`Preparing ${users.length} users (excluded from metrics)... `);
  await runWorkers(users, config.setupConcurrency, async (user) => {
    await axios.post(`${config.apiUrl}/api/auth/register`, user, {
      timeout: config.presignTimeout
    });
    const response = await axios.post(`${config.apiUrl}/api/auth/login`, {
      email: user.email,
      password: user.password
    }, { timeout: config.presignTimeout });
    user.token = response.data.token;
    user.sessionId = response.data.sessionId;
    if (!user.token || !user.sessionId) {
      throw new Error(`Login response is missing auth data for ${user.email}`);
    }
  });
  console.log('done');
  return users;
}

async function resolveRoom(users) {
  if (config.roomId) return config.roomId;
  const response = await axios.get(`${config.apiUrl}/api/rooms`, {
    headers: authHeaders(users[0]),
    timeout: config.presignTimeout
  });
  const room = response.data?.data?.find((candidate) => !candidate.hasPassword);
  if (!room?._id) throw new Error('No public room was found. Pass --room-id explicitly.');
  return room._id;
}

async function joinRoom(users, roomId) {
  process.stdout.write(`Joining room ${roomId} (excluded from metrics)... `);
  await runWorkers(users, config.setupConcurrency, async (user) => {
    const response = await axios.post(
      `${config.apiUrl}/api/rooms/${roomId}/join`,
      {},
      {
        headers: authHeaders(user),
        timeout: config.presignTimeout,
        validateStatus: () => true
      }
    );
    if (response.status !== 200 || response.data?.success === false) {
      throw new Error(`Room join failed: HTTP ${response.status}`);
    }
  });
  console.log('done');
}

async function runUploadLoad(users, roomId) {
  const presign = { latencies: [], statuses: new Map(), errors: [] };
  const put = { latencies: [], statuses: new Map(), errors: [] };
  const endToEndLatencies = [];
  const issuedFiles = [];
  const startedAt = process.hrtime.bigint();

  await runWorkers(users, config.concurrency, async (user) => {
    const flowStartedAt = process.hrtime.bigint();
    const presignStartedAt = process.hrtime.bigint();
    let response;
    try {
      response = await axios.post(
        `${config.apiUrl}/api/files/upload/presign`,
        { originalname, mimetype, size: image.length },
        {
          headers: { ...authHeaders(user), 'Content-Type': 'application/json' },
          timeout: config.presignTimeout,
          validateStatus: () => true
        }
      );
      presign.latencies.push(elapsedMs(presignStartedAt));
      presign.statuses.set(response.status, (presign.statuses.get(response.status) || 0) + 1);
      if (response.status !== 200 || !response.data?.uploadUrl) return;
      issuedFiles.push({ user, fileId: response.data.file?._id });
    } catch (error) {
      presign.latencies.push(elapsedMs(presignStartedAt));
      presign.errors.push(error.code || error.message);
      return;
    }

    const putStartedAt = process.hrtime.bigint();
    try {
      const putResponse = await axios.put(response.data.uploadUrl, image, {
        headers: { 'Content-Type': mimetype },
        timeout: config.putTimeout,
        maxBodyLength: Infinity,
        validateStatus: () => true
      });
      put.latencies.push(elapsedMs(putStartedAt));
      put.statuses.set(putResponse.status, (put.statuses.get(putResponse.status) || 0) + 1);
      if (putResponse.status >= 200 && putResponse.status < 300) {
        endToEndLatencies.push(elapsedMs(flowStartedAt));
      }
    } catch (error) {
      put.latencies.push(elapsedMs(putStartedAt));
      put.errors.push(error.code || error.message);
    }
  });

  const durationSeconds = Number(process.hrtime.bigint() - startedAt) / 1e9;
  console.log('\nFile Upload API load result');
  console.log(`API URL      : ${config.apiUrl}`);
  console.log(`Room         : ${roomId}`);
  console.log(`Users        : ${config.users}`);
  console.log(`Concurrency  : ${Math.min(config.concurrency, config.users)}`);
  console.log(`File         : ${originalname} (${image.length} bytes)`);
  console.log(`Duration     : ${durationSeconds.toFixed(2)} s`);
  summarize('Presign API', presign.latencies, presign.statuses, presign.errors,
    config.users, durationSeconds);
  summarize('S3 PUT', put.latencies, put.statuses, put.errors,
    config.users, durationSeconds);

  const sortedEndToEnd = [...endToEndLatencies].sort((a, b) => a - b);
  console.log('\nSuccessful end-to-end latency');
  console.log(`Completed    : ${sortedEndToEnd.length}/${config.users}`);
  console.log(`Latency p50  : ${percentile(sortedEndToEnd, 0.50).toFixed(1)} ms`);
  console.log(`Latency p95  : ${percentile(sortedEndToEnd, 0.95).toFixed(1)} ms`);
  console.log(`Latency p99  : ${percentile(sortedEndToEnd, 0.99).toFixed(1)} ms`);

  if (config.cleanupFiles && issuedFiles.length) {
    process.stdout.write(`Cleaning ${issuedFiles.length} issued file records... `);
    await runWorkers(issuedFiles, config.setupConcurrency, async ({ user, fileId }) => {
      if (!fileId) return;
      await axios.delete(`${config.apiUrl}/api/files/${fileId}`, {
        headers: authHeaders(user),
        timeout: config.presignTimeout,
        validateStatus: () => true
      });
    });
    console.log('done');
  }

  if (sortedEndToEnd.length !== config.users) process.exitCode = 1;
}

async function main() {
  console.log('Browser/Next.js/Socket.IO excluded: measuring Presign API + S3 PUT.');
  console.log('Generated users remain in the DB so room participant references stay valid.');
  const users = await prepareUsers();
  const roomId = await resolveRoom(users);
  await joinRoom(users, roomId);
  await runUploadLoad(users, roomId);
}

main().catch((error) => {
  const detail = error.response
    ? `HTTP ${error.response.status}: ${JSON.stringify(error.response.data)}`
    : error.message;
  console.error(`File upload load test failed: ${detail}`);
  process.exitCode = 1;
});
