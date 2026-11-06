import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const here=dirname(fileURLToPath(import.meta.url));
const id='0F0CA687-08B7-4D43-8D2C-C26834FD26E8';
const run=(...args)=>execFileSync('xcrun',['simctl',...args],{stdio:'inherit',timeout:120000});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const pose=name=>execFileSync('osascript',['-e',`tell application "System Events" to tell process "DeviceHub"
set frontmost to true
repeat with b in buttons of group 3 of splitter group 1 of group 1 of splitter group 1 of group 1 of window 1
if (help of b as text) is "${name}" then
click b
return
end if
end repeat
error "Duo pose button not found"
end tell`],{stdio:'inherit',timeout:30000});
const devices=JSON.parse(execFileSync('xcrun',['simctl','list','devices','available','--json'])).devices;
if(!Object.values(devices).flat().some(d=>d.udid===id&&d.state==='Booted'))run('boot',id);
run('bootstatus',id,'-b');
run('status_bar',id,'override','--time','9:41','--dataNetwork','wifi','--wifiMode','active','--wifiBars','3','--batteryState','charged','--batteryLevel','100');
await wait(5000);
async function capture(folder,display,locale,i,scenario){
 const dest=resolve(here,'editor/public/screenshots/apple',folder,locale);mkdirSync(dest,{recursive:true});
 execFileSync('xcrun',['simctl','launch','--terminate-running-process',id,'com.saschl.cameragps','-AppleLanguages',`(${locale})`,'-AppleLocale',locale==='de'?'de_DE':'en_US'],{stdio:'inherit',env:{...process.env,SIMCTL_CHILD_ALPHA_GPS_SCREENSHOT:scenario}});
 await wait(15000);
 const file=resolve(dest,`${String(i+1).padStart(2,'0')}.png`);
 run('io',id,'screenshot',`--display=${display}`,file);
 await wait(3000);run('io',id,'screenshot',`--display=${display}`,file);
}
try{
 pose('Closed');await wait(4000);
 if(!process.env.SCREENSHOT_SCENARIO)await capture('duo-outer/portrait','primary','en',0,'geotagging');
 pose('Open');await wait(5000);
 for(const locale of ['en','de'])for(const [i,scenario] of ['geotagging','reconnect','remote','wifi'].entries())if(!process.env.SCREENSHOT_SCENARIO || scenario===process.env.SCREENSHOT_SCENARIO)await capture('duo-inner/landscape','primary-1',locale,i,scenario);
}finally{pose('Closed');run('status_bar',id,'clear');run('terminate',id,'com.saschl.cameragps');run('shutdown',id);}
