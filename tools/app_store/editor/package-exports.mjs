import {mkdirSync,readdirSync,writeFileSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {execFileSync} from 'node:child_process';
import sharp from 'sharp';
const out=resolve('../../../artifacts/app-store-new');
const sets=[['iphone','1320x2868'],['ipad','2064x2752'],['duo-outer','1398x2034'],['duo-inner-landscape','2853x2007']];
for(const [device,size]of sets){
 const archive=join(out,`${device}.zip`);
 const files=execFileSync('unzip',['-Z1',archive],{encoding:'utf8'}).split('\n');
 for(const locale of ['en','de']){
  const matching=files.filter(f=>f.startsWith(`ios/${device}/${size}/${locale}/`)&&f.endsWith('.png'));
  if(matching.length!==4)throw new Error(`${archive}: expected four ${device} ${locale} slides at ${size}`);
 }
 execFileSync('unzip',['-oq',archive,'-d',join(out,'exports')]);
}
const manifest=[];const previews=[];
for(const [device,size]of sets){
 const [w,h]=size.split('x').map(Number);const tileW=300,tileH=Math.round(tileW*h/w);const tiles=[];
 for(const [row,locale]of ['en','de'].entries()){
  const folder=join(out,'exports/ios',device,size,locale);
  const files=readdirSync(folder).filter(f=>f.endsWith('.png')).sort();
  if(files.length!==4)throw new Error(`${folder}: expected four slides`);
  for(const [col,file]of files.entries()){
   const path=join(folder,file);const m=await sharp(path).metadata();
   if(m.width!==w||m.height!==h||m.hasAlpha)throw new Error(`Invalid PNG: ${path}`);
   manifest.push({device,locale,file:`exports/ios/${device}/${size}/${locale}/${file}`,width:w,height:h});
   tiles.push({input:await sharp(path).resize(tileW,tileH).toBuffer(),left:col*tileW,top:row*tileH});
  }
 }
 const preview=`${device}-preview.png`;
 await sharp({create:{width:1200,height:tileH*2,channels:3,background:'#fff'}}).composite(tiles).png().toFile(join(out,preview));
 previews.push(`<section><h2>${device} · ${size}</h2><p>English above · Deutsch unten</p><a href="${device}.zip">Download device bundle</a><img src="${preview}" alt="${device}: four slides in English and German"></section>`);
}
writeFileSync(join(out,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
writeFileSync(join(out,'index.html'),`<!doctype html><html lang="en"><meta charset="utf-8"><title>Alpha GPS · App Store screenshots</title><style>body{margin:40px auto;max-width:1300px;font:16px system-ui;background:#f6f1ea;color:#171717}section{margin:40px 0}img{display:block;max-width:1200px;width:100%;margin-top:20px}a{color:#405ed1}</style><h1>Alpha GPS</h1><p>Four slides per display · English and German · Actual simulator captures</p><a href="alpha-gps-new-screenshots.zip">Download all 32 primary screenshots</a>${previews.join('')}</html>`);
execFileSync('zip',['-q','alpha-gps-new-screenshots.zip','manifest.json',...manifest.map(m=>m.file)],{cwd:out});
console.log(`Verified ${manifest.length} opaque PNGs with exact dimensions.`);
const wifi=manifest.filter(m=>m.file.split('/').at(-1).startsWith('04-'));
if(wifi.length!==8)throw new Error('Expected eight localized Wi-Fi slides');
execFileSync('zip',['-q','alpha-gps-wifi-screenshots.zip',...wifi.map(m=>m.file)],{cwd:out});
const wifiTiles=[];
for(const [column,[device]]of sets.entries())for(const [row,locale]of ['en','de'].entries()){
 const file=wifi.find(m=>m.device===device&&m.locale===locale).file;
 wifiTiles.push({input:await sharp(join(out,file)).resize(300,652,{fit:'contain',background:'#eeeaf5'}).toBuffer(),left:column*300,top:row*652});
}
await sharp({create:{width:1200,height:1304,channels:3,background:'#eeeaf5'}}).composite(wifiTiles).png().toFile(join(out,'wifi-preview.png'));
