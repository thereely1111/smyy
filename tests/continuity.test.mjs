import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))
const root = path.resolve(import.meta.dirname, '..')
globalThis.window = globalThis
const mocks = {
  '@/api/http': `
    export const http = {
      get: async p => globalThis.__ct.get(p),
      put: async (p, b) => { globalThis.__ct.registered.push(b); globalThis.__ct.forms.push(b) },
      delete: async p => { globalThis.__ct.deleted.push(p) }
    }
    export async function httpStream(p, b, signal) {
      globalThis.__ct.requests.push(b)
      return globalThis.__ct.stream(p, b, signal)
    }`,
  '@/config': `export const AGENT_ID='test-agent', IS_MOCK=false, ARTIFACTS_POLL_MS=0, RETENTION_DAYS=7`,
  '@/api/artifact': `export async function fetchArtifactTree(){return []}
    export async function fetchArtifactContent(agent,session,path){return globalThis.__ct.readContent?.(path) ?? {path,content:''}}
    export async function saveArtifactContent(agent,session,path,content){return globalThis.__ct.saveContent?.(path,content)}`,
  '@/api/attachment': `export async function listAttachments(){return []}
    export async function deleteAttachment(){}; export function precheckFiles(){return []}
    export async function uploadAttachment(){}`,
  '@/stores/problemSession': `export function useProblemSessionStore(){return {}}`,
  '@/data/mockSandbox': `export const mockArtifactContent=()=>({content:''}),
    mockArtifactTree=()=>[], mockAttachments=()=>[], mockAguiEvents=()=>[]`,
  '@/engine/localRunner': `export async function runLocalCode(){throw new Error('not used')}`,
  // 实时通知流：本套测试只关心 store 的同步/中断语义，把流本身打桩，
  // 避免真去连 /api/notify/stream（那属于 notifyStream 自己的关注点）。
  '@/stores/notifyStream': `
    export function subscribeSessionActivity(){ return () => {} }
    export function isNotifyStreamConnected(){ return false }`,
}
const bundled = await build({
  stdin: {contents: `export {createPinia,setActivePinia} from 'pinia';
    export {useSandboxStore} from '@/stores/sandbox';
    export * from '@/api/session';
    export * from '@/api/agui';
    export {runProblemAnalysis} from '@/api/analysisRun';
    export {runAnimationStage} from '@/api/animRun';
    export {runCodeStage} from '@/api/codeRun';`, resolveDir: root, loader:'ts'},
  bundle:true, platform:'browser', format:'esm', write:false,
  define:{'process.env.NODE_ENV':'"production"'},
  plugins:[{name:'test-alias',setup(api){
    api.onResolve({filter:/^@\//}, a=>mocks[a.path] ? {path:a.path,namespace:'mock'} :
      {path:path.join(root,'src',a.path.slice(2))+'.ts'})
    api.onLoad({filter:/.*/,namespace:'mock'}, a=>({contents:mocks[a.path],loader:'js'}))
  }}]
})
const mod = await import('data:text/javascript;base64,'+Buffer.from(bundled.outputFiles[0].text).toString('base64'))
const success = {type:'RUN_FINISHED',outcome:{type:'success'}}
const sse = events => new Response(events.map(e=>'data: '+JSON.stringify(e)+'\n\n').join(''))
const gate = (...ids) => ({type:'RUN_FINISHED',outcome:{type:'interrupt',interrupts:ids.map(id=>({
  id:'reply:'+id,toolCallId:id,metadata:{toolName:'ui_form_advanced',toolInput:{title:id}}
}))}})
const deferred = () => {let resolve; const promise = new Promise(r=>resolve=r); return {promise,resolve}}
const remote = (id,role,text) => ({id,role,content:[{type:'text',text}]})
function setup() {
  const env = globalThis.__ct = {requests:[],registered:[],deleted:[],forms:[],transcript:[],progress:{},
    stream:async()=>sse([success]),
    get:async p=>p.endsWith('/transcript') ? {messages:env.transcript} :
      p.endsWith('/progress') ? env.progress : env.forms}
  mod.setActivePinia(mod.createPinia())
  return {env,store:mod.useSandboxStore()}
}
async function attached(t) {
  const context=setup()
  t.after(()=>context.store.detach())
  await context.store.attach('problem','session')
  return context
}
const send = store => store.send('求解', {threadId:'session',runId:'original-run'})

test('abort 能唤醒一直等数据的 SSE reader，且不投递晚到事件', async()=>{
  const ctl=new AbortController(); let cancelled=false; const events=[]
  const response=new Response(new ReadableStream({cancel(){cancelled=true}}))
  const parsing=mod.parseAguiStream(response,e=>events.push(e),ctl.signal)
  ctl.abort()
  await assert.rejects(parsing,{name:'AbortError'})
  assert.equal(cancelled,true); assert.deepEqual(events,[])
  const pre=new AbortController(); pre.abort()
  await assert.rejects(mod.parseAguiStream(sse([success]),()=>assert.fail('late event'),pre.signal),{name:'AbortError'})
})

test('流内错误不能被后续 RUN_FINISHED(success) 覆盖，RAW 错误也可见', async t=>{
  for(const failure of [{type:'RUN_ERROR',message:'会话忙'},{type:'RAW',event:{error:'会话忙'}}]) {
    const {env,store}=await attached(t)
    env.stream=async()=>sse([failure,success])
    await send(store)
    assert.equal(store.state,'error'); assert.equal(store.runError,'会话忙')
    store.detach()
  }
})

test('旧请求晚到或失败，不覆盖停止后新请求的消息和状态', async t=>{
  const {env,store}=await attached(t); const old=deferred(); const fresh=deferred()
  env.stream=()=>env.requests.length===1 ? old.promise : fresh.promise
  const first=send(store); store.stop(); const second=send(store)
  old.resolve(sse([{type:'RUN_ERROR',message:'旧错误'},success]))
  await first
  assert.equal(store.state,'running'); assert.equal(store.runError,'')
  fresh.resolve(sse([{type:'TEXT_MESSAGE_CONTENT',delta:'新回复'},success]))
  await second
  assert.equal(store.state,'idle'); assert.equal(store.runError,'')
  assert.equal(store.messages.at(-1).text,'新回复')
})

test('切换会话后旧 SSE 不得向新会话写消息或错误', async t=>{
  const {env,store}=await attached(t); const old=deferred()
  env.stream=()=>old.promise
  const first=send(store)
  await store.attach('other-problem','other-session')
  old.resolve(sse([{type:'TEXT_MESSAGE_CONTENT',delta:'旧会话'},success]))
  await first
  assert.equal(store.sessionId,'other-session'); assert.deepEqual(store.messages,[])
  assert.equal(store.state,'idle')
})

test('多表单逐张收集明确答复，不自动取消其它项，并复用原始 runId', async t=>{
  const {env,store}=await attached(t)
  env.stream=async()=>sse([gate('one','two')])
  await send(store)
  assert.equal(env.registered.length,2)
  assert.equal(env.registered[0].runId,'original-run')
  await store.resumeInterrupt('reply:one',{approved:true})
  assert.equal(env.requests.length,1)
  assert.equal(store.interrupts.length,1)
  await store.syncSession() // same pending batch must preserve answer
  assert.equal(store.interrupts.length,1)
  env.stream=async()=>{env.forms=[];return sse([success])}
  await store.resumeInterrupt('reply:two',{approved:false})
  assert.deepEqual(env.requests[1].resume,[
    {interruptId:'reply:one',status:'resolved',payload:{approved:true}},
    {interruptId:'reply:two',status:'resolved',payload:{approved:false}},
  ])
  assert.equal(env.requests[1].runId,'original-run')
  assert.deepEqual(env.deleted,[]) // no session-wide DELETE on success
})

test('刷新可恢复审批原始运行身份，续跑失败恢复全部表单', async t=>{
  const {env,store}=setup();t.after(()=>store.detach())
  env.forms=[{interruptId:'reply:one',toolCallId:'one',runId:'stage-run'}]
  await store.attach('problem','session')
  env.stream=async()=>sse([{type:'RUN_ERROR',message:'暂不可用'}])
  await store.resumeInterrupt('reply:one',{approved:true})
  assert.equal(env.requests[0].runId,'stage-run')
  assert.equal(store.state,'error'); assert.equal(store.interrupts.length,1)
})

test('只有显式整批取消才取消全部待确认项', async t=>{
  const {env,store}=await attached(t)
  env.stream=async()=>sse([gate('one','two')]);await send(store)
  await store.resumeInterrupt('reply:one',{approved:true})
  env.stream=async()=>{env.forms=[];return sse([success])}
  await store.cancelInterrupts()
  assert.equal(env.requests[1].resume.length,2)
  assert.ok(env.requests[1].resume.every(r=>r.status==='cancelled'))
})

test('表单超时或集合变化时丢弃暂存答复，不向失效闸门续跑', async t=>{
  const {env,store}=await attached(t)
  env.stream=async()=>sse([gate('one','two')]);await send(store)
  await store.resumeInterrupt('reply:one',{approved:true})
  env.forms=[];await store.syncSession()
  assert.deepEqual(store.interrupts,[]);assert.match(store.approvalNotice,/超时/)
  await store.resumeInterrupt('reply:two',{approved:true})
  assert.equal(env.requests.length,1)
})

test('只读同步显示后台续跑回复，不发送继续、不重复显示历史消息', async t=>{
  const {env,store}=await attached(t)
  env.transcript=[remote('u1','user','问题'),remote('a1','assistant','正在处理')]
  await store.syncSession()
  env.transcript.push(remote('a2','assistant','后台子任务已完成'))
  await store.syncSession();await store.syncSession()
  assert.deepEqual(store.messages.map(m=>m.text),['问题','正在处理','后台子任务已完成'])
  assert.equal(env.requests.length,0)
})

test('对话合并按 ID 优先，重复相同文本逐条匹配，保留未落库输入',()=>{
  const local=[{id:'optimistic1',role:'user',text:'继续',at:1,local:true},
    {id:'optimistic2',role:'user',text:'继续',at:2,local:true},
    {id:'not-saved',role:'user',text:'新输入',at:3,local:true}]
  const history=[remote('u1','user','继续'),remote('u2','user','继续'),remote('u2','user','继续')]
  const merged=mod.mergeTranscript(local,history)
  assert.deepEqual(merged.map(m=>m.id),['optimistic1','optimistic2','not-saved'])
  assert.deepEqual(mod.mergeTranscript(merged,history),merged)
  const incremental=mod.mergeTranscript([{id:'ui',remoteId:'a1',role:'assistant',text:'半句',at:1,local:true}],
    [remote('a1','assistant','完整回复')])
  assert.equal(incremental.length,1);assert.equal(incremental[0].text,'完整回复')
})

test('旧会话同步尚未完成时，新会话仍能立即加载历史', async t=>{
  const {env,store}=await attached(t);const old=deferred();const get=env.get
  env.get=p=>p.includes('/session/') ? old.promise : get(p)
  const syncing=store.syncSession()
  env.transcript=[remote('new','assistant','新会话历史')]
  await store.attach('new-problem','new-session')
  assert.equal(store.messages[0].text,'新会话历史')
  old.resolve({messages:[remote('old','assistant','旧历史')]});await syncing
  assert.equal(store.messages[0].text,'新会话历史')
})

test('快速切换文件时，旧响应不能覆盖新文件', async t=>{
  const {env,store}=await attached(t);const old=deferred()
  env.readContent=path=>path==='old.py' ? old.promise : {path,content:'new content'}
  const first=store.openFile({path:'old.py',name:'old.py',type:'file'})
  await store.openFile({path:'new.py',name:'new.py',type:'file'})
  old.resolve({path:'old.py',content:'old content'});await first
  assert.equal(store.selectedPath,'new.py');assert.equal(store.content.content,'new content')
  assert.equal(store.contentLoading,false)
})

test('读取失败不可编辑或保存，恢复后只保存已成功读取的文件', async t=>{
  const {env,store}=await attached(t);let saved=null
  env.readContent=()=>{throw new Error('network unavailable')}
  await store.openFile({path:'a.py',name:'a.py',type:'file'})
  store.editContent('untrusted')
  assert.match(store.contentError,/network unavailable/)
  assert.equal(store.content,null);assert.equal(await store.saveFile(),false)
  env.readContent=path=>({path,content:'original'})
  env.saveContent=(path,content)=>{saved={path,content}}
  await store.openFile({path:'a.py',name:'a.py',type:'file'})
  store.editContent('edited');assert.equal(store.dirty,true)
  assert.equal(await store.saveFile(),true);assert.deepEqual(saved,{path:'a.py',content:'edited'})
  assert.equal(store.dirty,false)
})

test('保存期间继续输入不会把新修改标记为已保存', async t=>{
  const {env,store}=await attached(t);const saving=deferred()
  env.readContent=path=>({path,content:'original'})
  env.saveContent=()=>saving.promise
  await store.openFile({path:'a.py',name:'a.py',type:'file'})
  store.editContent('first edit')
  const write=store.saveFile()
  store.editContent('second edit')
  saving.resolve();await write
  assert.equal(store.baseline,'first edit');assert.equal(store.content.content,'second edit')
  assert.equal(store.dirty,true)
})

for(const [name,run,request] of [
  ['S1',mod.runProblemAnalysis,{schema_version:'1.0',case_id:'case',problem:{title:'题目',text:'题目正文'},attachments:[]}],
  ['S2',mod.runAnimationStage,{case_id:'case',title:'题目'}],
  ['S3',mod.runCodeStage,{case_id:'case',title:'题目',question_id:1,model:{name:'模型'}}],
]) test(name+' 中断时登记实际表单及 runId，而非只提示去沙箱',async()=>{
  const {env}=setup();env.stream=async()=>sse([gate('stage')])
   await run(request,{threadId:'session',runId:name+'-run',timeoutMs:500})
   assert.equal(env.registered.length,1);assert.equal(env.registered[0].runId,name+'-run')
  assert.equal(env.registered[0].interruptId,'reply:stage')
})
