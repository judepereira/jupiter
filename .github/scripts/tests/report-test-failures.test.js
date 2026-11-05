const test = require('node:test');
const assert = require('node:assert/strict');
const report = require('../report-test-failures.js');
const {validReport, escapeMarkdown, split} = report._test;
const {execFileSync} = require('node:child_process');
const {mkdtempSync, rmSync} = require('node:fs');
const {tmpdir} = require('node:os');
const {join} = require('node:path');

function fixture(overrides = {}) {
  const prHead = 'c'.repeat(40);
  const currentRun = {id: 9, workflow_id: 3, run_attempt: 2, run_started_at: '2026-01-01T00:00:00Z',
    path: '.github/workflows/verify-and-publish.yml', event: 'pull_request', status: 'completed', conclusion: 'failure',
    head_sha: prHead, head_branch: 'feature', head_repository: {id: 11, full_name: 'fork/rep'}, repository: {id: 22},
    pull_requests: [{number: 7}], html_url: 'https://example/run'};
  const pr = {number: 7, state: 'open', head: {sha: prHead, ref: 'feature', repo: {id: 11}}, base: {repo: {id: 22}}};
  const comments = overrides.comments || [];
  const calls = {created: [], deleted: [], warnings: []};
  const github = {rest: {actions: {
    getWorkflowRun: async () => ({data: {...currentRun, ...overrides.run}}),
    listWorkflowRuns: async () => ({data: {workflow_runs: overrides.runs || [currentRun]}}),
    listWorkflowRunArtifacts: async () => ({data: {artifacts: [{id: 4, name: 'verify-test-results-2', size_in_bytes: 100, expired: false}]}}),
  }, issues: {
    listComments: async () => ({data: comments}),
    createComment: async ({body}) => { const c = {id: comments.length + 1, body, user: {login: 'github-actions[bot]'}}; comments.push(c); calls.created.push(body); return {data: c}; },
    deleteComment: async ({comment_id}) => { calls.deleted.push(comment_id); const i = comments.findIndex(c => c.id === comment_id); if (i >= 0) comments.splice(i, 1); },
  }, repos: {listPullRequestsAssociatedWithCommit: async () => ({data: [{number: 7}]})}, pulls: {
    get: async () => ({data: overrides.latestPr || pr}), list: async () => ({data: [pr]}),
  }}, paginate: async (method, params) => {
    if (method === github.rest.issues.listComments) return comments;
    if (method === github.rest.repos.listPullRequestsAssociatedWithCommit) return overrides.associations || [pr];
    if (method === github.rest.pulls.list) return [pr];
    if (method === github.rest.actions.listWorkflowRuns) return overrides.runs || [currentRun];
    throw new Error('unexpected pagination');
  }, request: async () => ({data: overrides.zip || zipReport({failures: [{class: 'Spec', name: 'works', message: ''}]})})};
  const context = {repo: {owner: 'o', repo: 'r'}, payload: {workflow_run: {id: 9, run_attempt: overrides.eventAttempt || 2, head_sha: prHead}, action: overrides.action || 'completed'}};
  return {github, context, calls, comments, core: {info() {}, warning: m => calls.warnings.push(m)}, prHead, currentRun};
}
function zipReport(extra = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'report-fixture-'));
  try {
    const json = JSON.stringify({schema_version: 1, build_sha: 'b'.repeat(40), head_sha: 'c'.repeat(40), build_exit_status: 1,
      reports_available: true, report_errors: [], failures: [], ...extra});
    const archive = join(dir, 'r.zip');
    execFileSync('python3', ['-c', 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1], "w"); z.writestr("results.json", sys.argv[2]); z.close()', archive, json]);
    return require('node:fs').readFileSync(archive);
  } finally { rmSync(dir, {recursive: true, force: true}); }
}
async function invoke(f) { await report(f); }

test('integration removes old bot report on start but preserves human and unrelated bot comments', async () => {
  const comments = [
    {id: 1, user: {login: 'github-actions[bot]'}, body: '<!-- jupiter-test-failure-report -->\\n<!-- run_id:8 attempt:1 --> old'},
    {id: 2, user: {login: 'human'}, body: '<!-- jupiter-test-failure-report --> human'},
    {id: 3, user: {login: 'github-actions[bot]'}, body: 'unrelated'},
  ];
  const f = fixture({action: 'in_progress', comments}); await invoke(f);
  assert.deepEqual(f.calls.deleted, [1]);
  assert.deepEqual(comments.map(c => c.id), [2, 3]);
});

test('integration success completion deletes reports without creating one', async () => {
  const comments = [{id: 1, user: {login: 'github-actions[bot]'}, body: '<!-- jupiter-test-failure-report -->\\n<!-- run_id:8 attempt:1 --> old'}];
  const f = fixture({run: {conclusion: 'success'}, comments}); await invoke(f);
  assert.deepEqual(f.calls.deleted, [1]); assert.equal(f.calls.created.length, 0);
});

test('integration ignores older run with newer updated timestamp', async () => {
  const newer = {...fixture().currentRun, id: 10, run_started_at: '2026-01-02T00:00:00Z', updated_at: '2026-01-02T00:00:00Z'};
  const older = {...fixture().currentRun, updated_at: '2026-01-03T00:00:00Z'};
  const f = fixture({run: older, runs: [older, newer]}); await invoke(f);
  assert.equal(f.calls.created.length, 0);
});

test('integration ignores event for older attempt', async () => {
  const f = fixture({eventAttempt: 1}); await invoke(f);
  assert.equal(f.calls.created.length, 0);
});

test('integration start event retains same-run attempt completion report', async () => {
  const comments = [{id: 1, user: {login: 'github-actions[bot]'}, body: '<!-- jupiter-test-failure-report -->\\n<!-- run_id:9 attempt:2 --> completed'}];
  const f = fixture({action: 'in_progress', comments}); await invoke(f);
  assert.deepEqual(f.calls.deleted, []); assert.equal(comments.length, 1);
});

test('integration falls back to listed PR when run associations are empty', async () => {
  const f = fixture({run: {pull_requests: []}, associations: []}); await invoke(f);
  assert.equal(f.calls.created.length, 1);
});

test('integration malformed ZIP warns and reports unavailable checkout SHA', async () => {
  const f = fixture({zip: Buffer.from('not a zip')}); await invoke(f);
  assert.ok(f.calls.warnings.length > 0);
  assert.match(f.calls.created[0], /Tested checkout SHA: `unavailable`/);
  assert.match(f.calls.created[0], /Head SHA: `c{40}`/);
  assert.doesNotMatch(f.calls.created[0], /Tested checkout SHA: `c{40}`/);
});

test('integration replaces partial multipart reports without omitting long names', async () => {
  const failures = Array.from({length: 5}, (_, i) => ({class: 'Suite', name: `test_${i}_${'n'.repeat(15000)}`, message: ''}));
  const old = {id: 1, user: {login: 'github-actions[bot]'}, body: '<!-- jupiter-test-failure-report -->\\n<!-- run_id:9 attempt:2 --> (part 1/99)'};
  const f = fixture({zip: zipReport({failures}), comments: [old]}); await invoke(f);
  assert.ok(f.calls.created.length > 1);
  assert.equal(f.calls.deleted.includes(1), true);
  for (let i = 0; i < failures.length; i++) assert.ok(f.calls.created.join('').includes(`test\\_${i}\\_`));
  assert.ok(f.calls.created.every(body => body.length <= 60000));
});

test('integration ignores workflow_run push event', async () => {
  const f = fixture({run: {event: 'push'}}); await invoke(f);
  assert.equal(f.calls.created.length, 0);
});

test('integration ignores current PR changed head', async () => {
  const f = fixture({latestPr: {...fixture().pr, head: {sha: 'd'.repeat(40), ref: 'feature', repo: {id: 11}}}});
  await invoke(f); assert.equal(f.calls.created.length, 0);
});

test('integration creates comment with tested build SHA and associated PR head SHA', async () => {
  const f = fixture(); await invoke(f);
  assert.equal(f.calls.created.length, 1);
  assert.match(f.calls.created[0], /Tested checkout SHA: `b{40}`/);
  assert.match(f.calls.created[0], /Head SHA: `c{40}`/);
});

test('integration rejects stale PR head and ignores non-PR runs', async () => {
  const stale = fixture({latestPr: {...fixture().pr, head: {sha: 'd'.repeat(40), ref: 'feature', repo: {id: 11}}}});
  await invoke(stale); assert.equal(stale.calls.created.length, 0);
  const nonpr = fixture({run: {event: 'push'}}); await invoke(nonpr); assert.equal(nonpr.calls.created.length, 0);
});

test('integration preserves partial failures, escapes markdown, and is idempotent', async () => {
  const f = fixture({zip: zipReport({reports_available: false, report_errors: ['bad <xml>'], failures: [{class: 'A', name: '@all', message: '*boom*'}]})});
  await invoke(f); assert.match(f.calls.created[0], /bad &lt;xml&gt;/); assert.match(f.calls.created[0], /@​all/);
  f.context.payload.workflow_run = {id: 9, run_attempt: 2, head_sha: sha};
  await invoke(f); assert.equal(f.calls.created.length, 1);
});

const sha = 'a'.repeat(40);
const run = {id: 1, run_attempt: 1, head_sha: sha};

test('validates report schema and binds report to trusted PR head', () => {
  const value = {schema_version: 1, build_sha: sha, head_sha: sha, build_exit_status: 1,
    reports_available: true, report_errors: [], failures: [{class: 'A', name: 'test', message: 'failed'}]};
  assert.equal(validReport(value, sha), true);
  assert.equal(validReport({...value, head_sha: 'b'.repeat(40)}, sha), false);
  assert.equal(validReport({...value, failures: Array(10001).fill(value.failures[0])}, sha), true);
  assert.equal(validReport({...value, report_errors: Array(101).fill('x')}, run, sha), false);
});

test('escapes markdown, control characters, and mentions', () => {
  const escaped = escapeMarkdown('@all <x> *a*\nnext');
  assert.match(escaped, /@\u200b/);
  assert.match(escaped, /&lt;x&gt;/);
  assert.doesNotMatch(escaped, /\n/);
  assert.match(escaped, /\\\*/);
});

test('splits entries within the limit and emits an empty-detail fallback', () => {
  assert.deepEqual(split(['12345', '67890', 'abc'], 6), ['12345', '67890', 'abc']);
  assert.deepEqual(split([], 20), ['No test details available.']);
});
