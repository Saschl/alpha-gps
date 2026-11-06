import {selectExportTargets} from './export-targets.mjs';
import {chromium} from 'playwright';
import {mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
const out=resolve('../../../artifacts/app-store-new');mkdirSync(out,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.SCREENSHOT_CHROME||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
try{
const page=await browser.newPage({viewport:{width:1600,height:1100},acceptDownloads:true});
page.on('console',m=>{if(m.type()==='error')console.log(m.text().slice(0,250));});
await page.goto(process.env.SCREENSHOT_EDITOR_URL || 'http://127.0.0.1:3000');await page.getByRole('button',{name:'Export bundle',exact:true}).waitFor();await page.waitForTimeout(3000);
for(const {key,label} of selectExportTargets({scope:process.env.EXPORT_SCOPE,device:process.env.EXPORT_DEVICE})){
 {
  await page.getByRole('combobox',{name:'Device',exact:true}).click();
  await page.getByRole('option',{name:label,exact:true}).click();
  await page.waitForTimeout(1000);
  const orientation=page.getByRole('combobox',{name:'Orientation',exact:true});
  if(key.startsWith('duo-')){
   await orientation.waitFor();
   await orientation.click();
   const desired=key==='duo-inner-landscape'?'Landscape':'Portrait';
   await page.getByRole('option',{name:desired,exact:true}).click();
   await page.waitForFunction(({desired})=>document.querySelector('[aria-label="Orientation"]')?.textContent?.includes(desired),{desired});
  }
 }
 await page.waitForTimeout(1500);
 await page.screenshot({path:resolve(out,`${key}-editor.png`)});
 const download=page.waitForEvent('download',{timeout:300000});
 await page.getByRole('button',{name:'Export bundle',exact:true}).click();
 const file=await download;await file.saveAs(resolve(out,`${key}.zip`));
 console.log('Exported',key);
}
}finally{await browser.close();}
