/** Verify real Rust native.parallel -> workflow.run -> workflow.run dispatch
 * using locally built artifacts, WITHOUT publishing or contacting cloud Jobs.
 * Supply AWFL_NATIVE_SERVER=/path/to/server and AWFL_NATIVE_LOCAL_BUILD_REPORT
 * for non-default checkouts.
 */
import { randomUUID, createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const server = process.env.AWFL_NATIVE_SERVER || '/workspace/server';
const report = resolve(process.env.AWFL_NATIVE_LOCAL_BUILD_REPORT || 'eval_runs/automationbench/native-coordinator-dev/build-report.json');
const { createNativeLocalRunner } = await import(pathToFileURL(resolve(server, 'workflows/native/localWorkflows.js')).href);
const { buildNativeTrustedGlobals } = await import(pathToFileURL(resolve(server, 'workflows/native/runner.js')).href);

const data = JSON.parse(await readFile(report, 'utf8'));
const names = new Map(), artifacts = new Map();
for (const [name, entry] of Object.entries(data)) {
  const manifest = JSON.parse(await readFile(entry.manifest, 'utf8'));
  const bytes = await readFile(entry.binary);
  const sha = createHash('sha256').update(bytes).digest('hex');
  if (sha !== entry.sha256 || manifest.workflow !== name || manifest.runtime_abi !== 3)
    throw Error('invalid native artifact ' + name);
  names.set(name, {workflowName:name, sha256:sha, protocolVersion:2,
    allowedOperations:entry.operations,
    ...(manifest.parallel_version === 1 && {parallelVersion:1}),
    ...(manifest.trusted_globals_version === 1 && {trustedGlobalsVersion:1}),
    ...(manifest.stream_ipc_version === 1 && {streamIpcVersion:1})});
  artifacts.set(sha, {sha256:sha,executable:entry.binary,cleanup:async () => {}});
}
const source = {active:async name => names.get(name)||null,materialize:async sha => artifacts.get(sha)};
const calledReleases = [], effects = [], progress = [];
const releases = { ...source, active: async name => { calledReleases.push(name); return source.active(name); } };
const workflowName = 'evals-AutomationBenchEval';
const release = await releases.active(workflowName);
const artifact = await releases.materialize(release.sha256);
const identity = { userId: 'native-preflight-user', projectId: 'native-preflight-project' };
const handlers = {};
for (const op of ['http.get','http.post','http.patch','http.delete','storage.object_get','callback.create','callback.await']) {
  handlers[op] = async ({ args }) => {
    effects.push({ op, url: args?.url?.split('?')[0], command: args?.body?.command, argKeys: Object.keys(args || {}).slice(0,10) });
    throw Error('intentionally stopped after first external effect');
  };
}
handlers['log.write'] = async ({args}) => { effects.push({op:'log.write'}); return null; };
const rootId='native_' + randomUUID().replaceAll('-','');
const runner = createNativeLocalRunner({
  releases, handlers, identity, rootExecutionId:rootId,
  gcpProject:'awfl-us',
  onProgress: p => progress.push({ workflow:p.workflow,stage:p.stage,depth:p.depth }),
});
let result, error;
try {
  result = await runner.run({workflowName,release,artifact,executionId:rootId,
    globals:buildNativeTrustedGlobals({state:{workflowName},executionId:rootId,gcpProject:'awfl-us'}),
    input: {arg1:'native-rust-preflight',arg2:'1',
      env: {BASE_URL:'http://127.0.0.1:5055',userId:identity.userId,
        projectId:identity.projectId,sessionId:'native-preflight-session',model:'gpt-6-sol'}} });
} catch(e) { error=String(e?.message||e); }
finally { await runner.close(); }
const summary = {root:workflowName,calledReleases,lanes:runner.parallelLaneCount,
  childCalls:runner.childCalls,effectCount:runner.effectCount,effects:effects.slice(0,12),
  progress:progress.slice(0,20),result,error};
console.log('AUTOMATIONBENCH_NATIVE_DISPATCH '+JSON.stringify(summary));
const reachedChild = calledReleases.includes('evals-experiments-AutomationBench')
  && calledReleases.includes('tools-CliTools') && runner.childCalls >= 2;
const reachedEffect = effects.some(x => x.op === 'http.post'
  && new URL(x.url).pathname === '/firebase/create');
if (!reachedChild || runner.parallelLaneCount < 1 || !reachedEffect ||
    !error?.includes('native effect failed')) {
  console.error('AUTOMATIONBENCH_NATIVE_DISPATCH_FAIL: expected the controlled first HTTP effect');
  process.exitCode=1;
} else {
  console.log('AUTOMATIONBENCH_NATIVE_DISPATCH_PASS lanes='+runner.parallelLaneCount
    +' childCalls='+runner.childCalls+' firstHttp=/firebase/create');
}
