const MARKER = '<!-- jupiter-test-failure-report -->';
const MAX_COMMENT = 60000;
const MAX_ARTIFACT = 10_000_000;
const MAX_JSON = 20_000_000;
const SHA = /^[a-f0-9]{40}$/i;

async function report({github, context, core}) {
  const eventRun = context.payload.workflow_run;
  const {owner, repo} = context.repo;
  const {data: run} = await github.rest.actions.getWorkflowRun({owner, repo, run_id: eventRun.id});
  if (run.path !== '.github/workflows/verify-and-publish.yml' || run.event !== 'pull_request'
      || run.run_attempt !== eventRun.run_attempt || run.head_sha !== eventRun.head_sha) {
    core.info('Ignoring a non-current or unexpected workflow run.');
    return;
  }
  const prs = await associatedPullRequests(github, owner, repo, run);
  if (prs.length !== 1) {
    core.info(`Ignoring run with ${prs.length} unambiguous associated pull requests.`);
    return;
  }
  const pr = prs[0];
  const issue_number = pr.number;
  const comments = await github.paginate(github.rest.issues.listComments, {owner, repo, issue_number, per_page: 100});
  const botComments = comments.filter(comment => comment.user?.login === 'github-actions[bot]' && comment.body?.startsWith(MARKER));
  const startEvent = context.payload.action === 'in_progress';
  if (startEvent) {
    if (!await isFresh(github, owner, repo, run, pr)) {
      return;
    }
    for (const comment of botComments) {
      const metadata = parseMarker(comment.body);
      if (metadata?.run_id === run.id && metadata.attempt === run.run_attempt) {
        continue;
      }
      if (await isFresh(github, owner, repo, run, pr)) {
        await github.rest.issues.deleteComment({owner, repo, comment_id: comment.id});
      }
    }
    return;
  }
  if (context.payload.action !== 'completed' || run.status !== 'completed') {
    core.info('Ignoring workflow_run event that is not a matching start or completion.');
    return;
  }
  if (!await isFresh(github, owner, repo, run, pr)) {
    return;
  }
  if (run.conclusion === 'success') {
    for (const comment of botComments) {
      if (await isFresh(github, owner, repo, run, pr)) {
        await github.rest.issues.deleteComment({owner, repo, comment_id: comment.id});
      }
    }
    return;
  }
  const content = await loadReport(github, owner, repo, run, pr.head.sha, core);
  let entries = [];
  let title;
  if (content === null) {
    title = 'Test results unavailable';
    entries = [`The verification run failed (${run.conclusion}), but a valid test report was unavailable.`];
  } else {
    entries = content.failures.map(failure => `- **${escapeMarkdown(`${failure.class}.${failure.name}`)}**${failure.message ? ` - ${escapeMarkdown(failure.message)}` : ''}`);
    if (!content.reports_available || content.report_errors.length) {
      title = 'Test report incomplete';
      entries.push(`Diagnostic: ${escapeMarkdown(content.report_errors.join('; ') || 'No Surefire reports found.')}`);
    } else if (!entries.length) {
      title = run.conclusion === 'cancelled' ? 'Verification cancelled' : 'Build failed without reported test failures';
      entries.push(run.conclusion === 'cancelled' ? 'The verification run was cancelled; no test failures were reported.' : 'The build failed without any reported test failures.');
    } else {
      title = `${content.failures.length} failed test${content.failures.length === 1 ? '' : 's'}`;
    }
  }
  const marker = `${MARKER}\n<!-- run_id:${run.id} attempt:${run.run_attempt} -->`;
  const head = `${marker}\n## ${title}\nTested checkout SHA: \`${content?.build_sha || 'unavailable'}\`\nHead SHA: \`${pr.head.sha}\`\nRun: ${run.html_url}`;
  const chunks = split(entries, MAX_COMMENT - head.length - 100);
  const bodies = chunks.map((chunk, index) => `${head}${chunks.length > 1 ? ` (part ${index + 1}/${chunks.length})` : ''}\n\n${chunk}`);
  const existing = botComments.filter(comment => {
    const metadata = parseMarker(comment.body);
    return metadata?.run_id === run.id && metadata.attempt === run.run_attempt;
  });
  if (existing.length === bodies.length && bodies.every(body => existing.some(comment => comment.body === body))) {
    return;
  }
  for (const comment of botComments) {
    if (await isFresh(github, owner, repo, run, pr)) {
      await github.rest.issues.deleteComment({owner, repo, comment_id: comment.id});
    }
  }
  for (const body of bodies) {
    if (!await isFresh(github, owner, repo, run, pr)) {
      return;
    }
    await github.rest.issues.createComment({owner, repo, issue_number, body});
  }
}

async function associatedPullRequests(github, owner, repo, run) {
  const association = await github.paginate(github.rest.repos.listPullRequestsAssociatedWithCommit, {owner, repo, commit_sha: run.head_sha, per_page: 100});
  const numbers = new Set([...(run.pull_requests || []).map(item => item.number), ...association.map(item => item.number)]);
  if (run.head_repository?.full_name && run.head_branch) {
    const [headOwner] = run.head_repository.full_name.split('/');
    const response = await github.paginate(github.rest.pulls.list, {owner, repo, state: 'open', head: `${headOwner}:${run.head_branch}`, per_page: 100});
    for (const item of response) {
      numbers.add(item.number);
    }
  }
  const matches = [];
  for (const number of numbers) {
    const {data: pr} = await github.rest.pulls.get({owner, repo, pull_number: number});
    if (pr.state === 'open' && pr.head.sha === run.head_sha && pr.head.ref === run.head_branch
        && pr.head.repo?.id === run.head_repository?.id && pr.base.repo?.id === run.repository?.id) {
      matches.push(pr);
    }
  }
  return matches;
}

async function isFresh(github, owner, repo, run, pr) {
  const {data: latestPr} = await github.rest.pulls.get({owner, repo, pull_number: pr.number});
  if (latestPr.state !== 'open' || latestPr.head.sha !== run.head_sha || latestPr.head.ref !== run.head_branch
      || latestPr.head.repo?.id !== run.head_repository?.id || latestPr.base.repo?.id !== run.repository?.id) {
    return false;
  }
  const latest = await newestApplicableRun(github, owner, repo, run);
  return latest.id === run.id && latest.run_attempt === run.run_attempt;
}

async function newestApplicableRun(github, owner, repo, run) {
  const runs = await github.paginate(github.rest.actions.listWorkflowRuns, {
    owner, repo, workflow_id: run.workflow_id, head_sha: run.head_sha, per_page: 100,
  });
  const applicable = runs.filter(item => item.event === 'pull_request' && item.head_sha === run.head_sha
    && item.head_branch === run.head_branch && item.head_repository?.id === run.head_repository?.id
    && item.repository?.id === run.repository?.id);
  applicable.sort((left, right) => Date.parse(right.run_started_at || right.created_at) - Date.parse(left.run_started_at || left.created_at) || right.id - left.id);
  return applicable[0] || {};
}

async function loadReport(github, owner, repo, run, trustedHeadSha, core) {
  const {data} = await github.rest.actions.listWorkflowRunArtifacts({owner, repo, run_id: run.id, per_page: 100});
  const artifacts = data.artifacts.filter(item => item.name === `verify-test-results-${run.run_attempt}` && !item.expired);
  if (artifacts.length !== 1 || artifacts[0].size_in_bytes > MAX_ARTIFACT) {
    return null;
  }
  const response = await github.request('GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/{archive_format}', {
    owner, repo, artifact_id: artifacts[0].id, archive_format: 'zip', headers: {accept: 'application/vnd.github+json'},
  });
  const {execFileSync} = require('node:child_process');
  const {mkdtempSync, writeFileSync, rmSync} = require('node:fs');
  const {tmpdir} = require('node:os');
  const {join} = require('node:path');
  const dir = mkdtempSync(join(tmpdir(), 'test-report-'));
  try {
    const archive = join(dir, 'report.zip');
    const bytes = Buffer.from(response.data);
    if (bytes.length > MAX_ARTIFACT) {
      throw new Error('Artifact download exceeds size limit.');
    }
    writeFileSync(archive, bytes);
    const output = execFileSync('python3', ['-c', 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); names=[n for n in z.namelist() if n == "results.json"]; assert len(names)==1; i=z.getinfo(names[0]); assert i.file_size<=20000000; d=z.read(i); assert len(d)<=20000000; sys.stdout.buffer.write(d)', archive], {maxBuffer: MAX_JSON + 1});
    const value = JSON.parse(output.toString('utf8'));
    if (!validReport(value, trustedHeadSha)) {
      throw new Error('Artifact report schema is invalid.');
    }
    return value;
  } catch (error) {
    core.warning(`Unable to read test-results artifact: ${error.message}`);
    return null;
  } finally {
    rmSync(dir, {recursive: true, force: true});
  }
}

function validReport(value, trustedHeadSha) {
  return value && value.schema_version === 1 && SHA.test(value.build_sha)
    && SHA.test(value.head_sha) && value.head_sha === trustedHeadSha && Number.isInteger(value.build_exit_status)
    && typeof value.reports_available === 'boolean' && Array.isArray(value.report_errors)
    && value.report_errors.length <= 100 && value.report_errors.every(item => typeof item === 'string' && item.length <= 2000)
    && Array.isArray(value.failures)
    && value.failures.every(item => item && typeof item.class === 'string'
      && typeof item.name === 'string'
      && typeof item.message === 'string' && item.message.length <= 4000);
}

function escapeMarkdown(value) {
  return String(value).replace(/[\r\n\u0000-\u001f\u007f]/g, ' ').replace(/@/g, '@\u200b')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/([\\`*_{}\[\]()#+.!|>~-])/g, '\\$1');
}

function split(items, limit) {
  const chunks = [];
  let current = '';
  for (const item of items) {
    let text = String(item);
    while (text.length > limit) {
      if (current) {
        chunks.push(current);
        current = '';
      }
      chunks.push(text.slice(0, limit));
      text = text.slice(limit);
    }
    if (current && current.length + text.length + 1 > limit) {
      chunks.push(current);
      current = '';
    }
    current += `${current ? '\n' : ''}${text}`;
  }
  if (current || chunks.length === 0) {
    chunks.push(current || 'No test details available.');
  }
  return chunks;
}

function parseMarker(body) {
  const match = body.match(/<!-- run_id:(\d+) attempt:(\d+) -->/);
  return match ? {run_id: Number(match[1]), attempt: Number(match[2])} : null;
}


module.exports = report;
module.exports._test = {validReport, escapeMarkdown, split, newestApplicableRun, associatedPullRequests};
