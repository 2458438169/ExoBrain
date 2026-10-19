import assert from 'node:assert/strict';
import test from 'node:test';
import { buildTurns, isReadableAnswer } from '../src/components/views/agentChatView/chatTurns.ts';

const message = (id, role, content = '', runId) => ({id, sessionId:'session', role, content, metadata: runId ? {runId} : null});
const run = (id, question, answer, status = 'SUCCEEDED') => ({runId:id, triggeringMessageId:question, answerMessageId:answer, status});

test('late snapshot message remains with its server-associated question', () => {
  const turns = buildTurns([message('q1','user','first'), message('q2','user','second'),
    message('a2','assistant','second answer','r2'), message('a1','assistant','first answer','r1')],
    [run('r1','q1','a1'),run('r2','q2','a2')]);
  assert.deepEqual(turns.map(t=>[t.question.id,t.answers.map(m=>m.id),t.run.runId]),
    [['q1',['a1'],'r1'],['q2',['a2'],'r2']]);
});

test('empty assistant tool calls never become answer anchors', () => {
  const call = {...message('call','assistant','','r1'), metadata:{runId:'r1',toolCalls:[{id:'t1',name:'KnowledgeTool'}]}};
  const turns = buildTurns([message('q','user','question'),call,message('tool','tool','result','r1'),
    message('a','assistant','answer','r1')],[run('r1','q','a')]);
  assert.equal(turns.length,1);
  assert.equal(turns[0].answers.length,1);
  assert.equal(turns[0].traceMessages.length,1);
  assert.equal(turns[0].tools.length,1);
  assert.equal(isReadableAnswer(call),false);
});

test('answerless cancelled run stays on its own question after a later answer', () => {
  const turns=buildTurns([message('q1','user','cancelled'),message('q2','user','next'),message('a2','assistant','ok','r2')],
    [run('r1','q1',null,'CANCELLED'),run('r2','q2','a2')]);
  assert.equal(turns[0].answers.length,0);
  assert.equal(turns[0].run.status,'CANCELLED');
  assert.equal(turns[1].answers[0].id,'a2');
});

test('a missing association does not borrow the adjacent run or its final status', () => {
  const turns=buildTurns([message('q','user','question'),message('old','assistant','legacy answer')],[run('r1','q',null,'FAILED')]);
  assert.equal(turns[0].answers.length,0);
  assert.equal(turns[1].run,undefined);
  assert.equal(turns[1].answers[0].id,'old');
});

test('a server message association takes precedence over inconsistent metadata', () => {
  const turns=buildTurns([message('q','user','question'),message('a','assistant','answer','old')],[run('r1','q','a')]);
  assert.equal(turns.length,1);
  assert.equal(turns[0].run.runId,'r1');
});

test('public legacy tool records have one separate disclosure without guessed run IDs', () => {
  const call={...message('call','assistant'),metadata:{toolCalls:[{id:'t1',name:'KnowledgeTool'}]}};
  const turns=buildTurns([message('q','user','question'),call,message('tool','tool','result'),message('a','assistant','answer')],[]);
  assert.equal(turns.filter(t=>t.traceMessages.length||t.tools.length).length,1);
  assert.equal(turns.at(-1).key,'unassociated-tools');
  assert.ok(turns.every(t=>!t.run));
});
