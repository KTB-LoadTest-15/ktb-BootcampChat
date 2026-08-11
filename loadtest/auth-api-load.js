#!/usr/bin/env node

const axios = require('axios');
const yargs = require('yargs/yargs');
const { hideBin } = require('yargs/helpers');

const cliArgs = hideBin(process.argv);
// `pnpm run <script> -- --option=value` may forward the separator itself.
// Remove only that leading separator so the following load options are parsed.
if (cliArgs[0] === '--') cliArgs.shift();

const argv = yargs(cliArgs)
  .option('api-url', {
    description: 'Backend REST API URL',
    type: 'string',
    default: 'http://localhost:5001'
  })
  .option('users', {
    alias: 'u',
    description: 'Number of unique login users',
    type: 'number',
    default: 10
  })
  .option('requests', {
    alias: 'n',
    description: 'Total number of measured login requests',
    type: 'number',
    default: 100
  })
  .option('concurrency', {
    alias: 'c',
    description: 'Number of concurrent login workers',
    type: 'number',
    default: 10
  })
  .option('timeout', {
    description: 'Request timeout in milliseconds',
    type: 'number',
    default: 10000
  })
  .strict()
  .help()
  .parse();

const config = {
  apiUrl: argv.apiUrl.replace(/\/$/, ''),
  users: Math.max(1, Math.floor(argv.users)),
  requests: Math.max(1, Math.floor(argv.requests)),
  concurrency: Math.max(1, Math.floor(argv.concurrency)),
  timeout: Math.max(1, Math.floor(argv.timeout))
};

const password = 'LoadTest1234!';
const runId = `${Date.now()}-${process.pid}`;

function percentile(sortedValues, ratio) {
  if (sortedValues.length === 0) return 0;
  const index = Math.ceil(sortedValues.length * ratio) - 1;
  return sortedValues[Math.max(0, index)];
}

async function prepareUsers() {
  const users = Array.from({ length: config.users }, (_, index) => ({
    email: `auth-api-${runId}-${index}@test.com`,
    password,
    name: `Auth API User ${index}`
  }));

  process.stdout.write(`Preparing ${users.length} users (excluded from metrics)... `);
  await Promise.all(users.map((user) => axios.post(
    `${config.apiUrl}/api/auth/register`,
    user,
    { timeout: config.timeout }
  )));
  console.log('done');
  return users;
}

async function runLoad(users) {
  let nextRequest = 0;
  const latencies = [];
  const statusCounts = new Map();
  const errors = [];
  const startedAt = process.hrtime.bigint();

  async function worker() {
    while (true) {
      const requestIndex = nextRequest++;
      if (requestIndex >= config.requests) return;

      const user = users[requestIndex % users.length];
      const requestStartedAt = process.hrtime.bigint();

      try {
        const response = await axios.post(
          `${config.apiUrl}/api/auth/login`,
          { email: user.email, password: user.password },
          {
            timeout: config.timeout,
            validateStatus: () => true
          }
        );
        const elapsedMs = Number(process.hrtime.bigint() - requestStartedAt) / 1e6;
        latencies.push(elapsedMs);
        statusCounts.set(response.status, (statusCounts.get(response.status) || 0) + 1);
      } catch (error) {
        const elapsedMs = Number(process.hrtime.bigint() - requestStartedAt) / 1e6;
        latencies.push(elapsedMs);
        errors.push(error.code || error.message);
      }
    }
  }

  const workerCount = Math.min(config.concurrency, config.requests);
  await Promise.all(Array.from({ length: workerCount }, () => worker()));

  const durationSeconds = Number(process.hrtime.bigint() - startedAt) / 1e9;
  const sorted = [...latencies].sort((a, b) => a - b);
  const successCount = statusCounts.get(200) || 0;
  const average = sorted.reduce((sum, value) => sum + value, 0) / sorted.length;

  console.log('\nLogin API load result');
  console.log(`Target       : ${config.apiUrl}/api/auth/login`);
  console.log(`Users        : ${config.users}`);
  console.log(`Requests     : ${config.requests}`);
  console.log(`Concurrency  : ${workerCount}`);
  console.log(`Success      : ${successCount}/${config.requests} (${(successCount / config.requests * 100).toFixed(1)}%)`);
  console.log(`Status       : ${[...statusCounts.entries()].sort().map(([status, count]) => `${status}=${count}`).join(', ') || '-'}`);
  console.log(`Errors       : ${errors.length}${errors.length ? ` (${errors.slice(0, 5).join(', ')})` : ''}`);
  console.log(`Duration     : ${durationSeconds.toFixed(2)} s`);
  console.log(`Throughput   : ${(config.requests / durationSeconds).toFixed(2)} req/s`);
  console.log(`Latency avg  : ${average.toFixed(1)} ms`);
  console.log(`Latency p50  : ${percentile(sorted, 0.50).toFixed(1)} ms`);
  console.log(`Latency p90  : ${percentile(sorted, 0.90).toFixed(1)} ms`);
  console.log(`Latency p95  : ${percentile(sorted, 0.95).toFixed(1)} ms`);
  console.log(`Latency p99  : ${percentile(sorted, 0.99).toFixed(1)} ms`);

  if (successCount !== config.requests) process.exitCode = 1;
}

async function main() {
  console.log('Browser/Next.js excluded: measuring register setup + login API only.');
  const users = await prepareUsers();
  await runLoad(users);
}

main().catch((error) => {
  const detail = error.response
    ? `HTTP ${error.response.status}: ${JSON.stringify(error.response.data)}`
    : error.message;
  console.error(`Load test setup failed: ${detail}`);
  process.exitCode = 1;
});
