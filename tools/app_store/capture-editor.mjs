import {execFileSync} from 'node:child_process';
import {mkdirSync, statSync, copyFileSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const here=dirname(fileURLToPath(import.meta.url));
const app=process.argv[2];
if (!app) throw new Error('Pass the Debug simulator alphagps.app path.');
copyFileSync(resolve(here,'assets/wifi-preview.jpg'),resolve(app,'wifi-preview.jpg'));
const run=(...args)=>execFileSync('xcrun',['simctl',...args],{stdio:'inherit',timeout:180000});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const devices=JSON.parse(execFileSync('xcrun',['simctl','list','devices','available','--json'])).devices;
const targets=[['iphone','iPhone 18 Pro Max'],['ipad','iPad Pro 13-inch (M5)'],['duo-outer/portrait','iPhone Duo']];
for(const [folder,name] of targets.filter(([folder])=>!process.env.CAPTURE_TARGET || folder===process.env.CAPTURE_TARGET)){
 const device=Object.values(devices).flat().find(d=>d.name===name);
 if(!device) throw new Error(`Missing simulator ${name}`);
 const id=device.udid;
 if(device.state!=='Booted')run('boot',id);
 run('bootstatus',id,'-b');run('install',id,resolve(app));run('ui',id,'appearance','light');
 run('status_bar',id,'override','--time','9:41','--dataNetwork','wifi','--wifiMode','active','--wifiBars','3','--batteryState','charged','--batteryLevel','100');
 try{
 for(const locale of ['en','de'])for(const [i,scenario] of ['geotagging','reconnect','remote','wifi'].entries()){
  if(process.env.SCREENSHOT_SCENARIO && scenario!==process.env.SCREENSHOT_SCENARIO)continue;
  const dest=resolve(here,'editor/public/screenshots/apple',folder,locale);mkdirSync(dest,{recursive:true});
  execFileSync('xcrun',['simctl','launch','--terminate-running-process',id,'com.saschl.cameragps','-AppleLanguages',`(${locale})`,'-AppleLocale',locale==='de'?'de_DE':'en_US'],{stdio:'inherit',env:{...process.env,SIMCTL_CHILD_ALPHA_GPS_SCREENSHOT:scenario},timeout:60000});
  const file=resolve(dest,`${String(i+1).padStart(2,'0')}.png`);
  for(let attempt=0;attempt<6;attempt++){
   await wait(15000);
   run('io',id,'screenshot','--display=primary',file);
   if(statSync(file).size>150000)break;
   if(attempt===5)throw new Error(`Blank capture: ${file}`);
  }
 }
 }finally{run('status_bar',id,'clear');run('terminate',id,'com.saschl.cameragps');run('shutdown',id);}
}
